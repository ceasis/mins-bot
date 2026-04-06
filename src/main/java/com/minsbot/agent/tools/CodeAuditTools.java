package com.minsbot.agent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Code audit tools: clone repos, scan for vulnerabilities, hardcoded secrets,
 * unused imports, and generate markdown reports.
 */
@Component
public class CodeAuditTools {

    private final ToolExecutionNotifier notifier;

    private static final Path TEMP_DIR = Paths.get(
            System.getProperty("user.home"), "mins_bot_data", "code_audit");

    // ── SQL injection patterns ──────────────────────────────────────────────
    private static final List<PatternDef> SQL_INJECTION_PATTERNS = List.of(
        new PatternDef("String concatenation in SQL query",
            Pattern.compile("(?i)(execute|cursor\\.execute|query|rawQuery|executeQuery|executeUpdate)\\s*\\([^)]*[\"'][^\"']*[\"']\\s*\\+"), "HIGH"),
        new PatternDef("f-string in SQL query",
            Pattern.compile("(?i)(execute|cursor\\.execute)\\s*\\(\\s*f[\"']"), "HIGH"),
        new PatternDef("Format string in SQL query",
            Pattern.compile("(?i)(execute|cursor\\.execute)\\s*\\([^)]*\\.format\\s*\\("), "HIGH"),
        new PatternDef("Percent formatting in SQL query",
            Pattern.compile("(?i)(execute|cursor\\.execute)\\s*\\([^)]*%\\s*\\("), "MEDIUM"),
        new PatternDef("Raw SQL string with variable interpolation",
            Pattern.compile("(?i)(SELECT|INSERT|UPDATE|DELETE|DROP|ALTER)\\s+.*\\{.*\\}"), "MEDIUM"),
        new PatternDef("Raw SQL with string concatenation",
            Pattern.compile("(?i)[\"'](SELECT|INSERT|UPDATE|DELETE|DROP)\\s+.*[\"']\\s*\\+\\s*"), "HIGH")
    );

    // ── Hardcoded secret patterns ───────────────────────────────────────────
    private static final List<PatternDef> SECRET_PATTERNS = List.of(
        new PatternDef("Hardcoded API key assignment",
            Pattern.compile("(?i)(api[_-]?key|apikey)\\s*=\\s*[\"'][A-Za-z0-9_\\-]{16,}[\"']"), "CRITICAL"),
        new PatternDef("Hardcoded secret/token assignment",
            Pattern.compile("(?i)(secret|token|password|passwd|pwd)\\s*=\\s*[\"'][^\"']{8,}[\"']"), "CRITICAL"),
        new PatternDef("AWS access key",
            Pattern.compile("AKIA[0-9A-Z]{16}"), "CRITICAL"),
        new PatternDef("Private key marker",
            Pattern.compile("-----BEGIN (RSA |EC |DSA )?PRIVATE KEY-----"), "CRITICAL"),
        new PatternDef("Generic bearer token",
            Pattern.compile("(?i)(bearer|authorization)\\s*[:=]\\s*[\"'][A-Za-z0-9_\\-.]{20,}[\"']"), "HIGH"),
        new PatternDef("Hardcoded connection string with password",
            Pattern.compile("(?i)(jdbc|mongodb|mysql|postgres|redis|amqp)://[^\\s\"']*:[^\\s\"']*@"), "HIGH"),
        new PatternDef("GitHub/GitLab token pattern",
            Pattern.compile("(ghp_[A-Za-z0-9]{36}|glpat-[A-Za-z0-9\\-]{20,})"), "CRITICAL"),
        new PatternDef("Slack token",
            Pattern.compile("xox[bpors]-[0-9A-Za-z\\-]{10,}"), "CRITICAL")
    );

    // ── Unused import patterns (Python) ─────────────────────────────────────
    private static final Pattern PYTHON_IMPORT = Pattern.compile(
            "^\\s*(import\\s+([\\w.]+)(?:\\s+as\\s+(\\w+))?|from\\s+[\\w.]+\\s+import\\s+(.+))\\s*$");

    public CodeAuditTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Tools
    // ═════════════════════════════════════════════════════════════════════════

    @Tool(description = "Clone a git repository to a temporary folder for code auditing. "
            + "Returns the local path where the repo was cloned. Requires git to be installed.")
    public String cloneRepo(
            @ToolParam(description = "Git repository URL, e.g. https://github.com/user/project") String repoUrl) {
        notifier.notify("Cloning " + repoUrl + "...");
        try {
            Files.createDirectories(TEMP_DIR);
            // Extract repo name from URL
            String repoName = repoUrl.replaceAll("\\.git$", "")
                    .replaceAll(".*/", "")
                    .replaceAll("[^a-zA-Z0-9_\\-]", "_");
            String dirName = repoName + "_" + System.currentTimeMillis();
            Path clonePath = TEMP_DIR.resolve(dirName);

            ProcessBuilder pb = new ProcessBuilder("git", "clone", "--depth", "1", repoUrl, clonePath.toString());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = proc.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return "Error: git clone timed out after 120 seconds.";
            }
            if (proc.exitValue() != 0) {
                return "Error cloning repo: " + output.trim();
            }
            return "Repository cloned to: " + clonePath.toAbsolutePath()
                    + "\nFiles: " + countFiles(clonePath);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "Scan source code files in a directory for security vulnerabilities: "
            + "SQL injection, hardcoded secrets, and unused imports. "
            + "Returns categorized findings with file paths, line numbers, and severity levels.")
    public String scanCode(
            @ToolParam(description = "Full path to the directory to scan") String directory,
            @ToolParam(description = "File extensions to scan, comma-separated, e.g. 'py,js,java,rb,php'") String extensions) {
        notifier.notify("Scanning code in " + directory + "...");
        try {
            Path dir = Paths.get(directory).toAbsolutePath();
            if (!Files.isDirectory(dir)) return "Not a directory: " + dir;

            Set<String> exts = Arrays.stream(extensions.split(","))
                    .map(String::trim)
                    .map(e -> e.startsWith(".") ? e : "." + e)
                    .collect(Collectors.toSet());

            List<Finding> allFindings = new ArrayList<>();
            List<Path> files = new ArrayList<>();

            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    String ext = name.contains(".") ? name.substring(name.lastIndexOf(".")) : "";
                    if (exts.contains(ext.toLowerCase())) files.add(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    String name = d.getFileName().toString();
                    if (name.equals(".git") || name.equals("node_modules") || name.equals("__pycache__")
                            || name.equals(".venv") || name.equals("venv") || name.equals(".tox")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            notifier.notify("Scanning " + files.size() + " files...");

            for (Path file : files) {
                try {
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    String relPath = dir.relativize(file).toString().replace('\\', '/');
                    String ext = file.getFileName().toString();
                    ext = ext.contains(".") ? ext.substring(ext.lastIndexOf(".") + 1) : "";

                    // SQL injection scan
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        for (PatternDef pd : SQL_INJECTION_PATTERNS) {
                            if (pd.pattern.matcher(line).find()) {
                                allFindings.add(new Finding("SQL Injection", pd.severity,
                                        pd.name, relPath, i + 1, line.trim()));
                            }
                        }
                    }

                    // Hardcoded secrets scan
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        for (PatternDef pd : SECRET_PATTERNS) {
                            if (pd.pattern.matcher(line).find()) {
                                // Skip comments and obvious test/example lines
                                String trimmed = line.trim();
                                if (trimmed.startsWith("#") || trimmed.startsWith("//")
                                        || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue;
                                if (trimmed.contains("example") || trimmed.contains("placeholder")
                                        || trimmed.contains("your_") || trimmed.contains("xxx")) continue;
                                allFindings.add(new Finding("Hardcoded Secret", pd.severity,
                                        pd.name, relPath, i + 1, maskSecrets(line.trim())));
                            }
                        }
                    }

                    // Unused imports scan (Python)
                    if (ext.equals("py")) {
                        scanPythonUnusedImports(lines, relPath, allFindings);
                    }

                } catch (Exception e) {
                    // Skip files that can't be read (binary, encoding issues)
                }
            }

            // Build summary
            StringBuilder sb = new StringBuilder();
            sb.append("Code Scan Results for: ").append(dir).append("\n");
            sb.append("Files scanned: ").append(files.size()).append("\n");
            sb.append("Total findings: ").append(allFindings.size()).append("\n\n");

            Map<String, List<Finding>> byCategory = allFindings.stream()
                    .collect(Collectors.groupingBy(f -> f.category, LinkedHashMap::new, Collectors.toList()));

            for (var entry : byCategory.entrySet()) {
                sb.append("═══ ").append(entry.getKey()).append(" (")
                        .append(entry.getValue().size()).append(") ═══\n");
                for (Finding f : entry.getValue()) {
                    sb.append("  [").append(f.severity).append("] ")
                            .append(f.file).append(":").append(f.line)
                            .append(" — ").append(f.name).append("\n");
                    sb.append("    ").append(f.snippet).append("\n");
                }
                sb.append("\n");
            }

            // Severity summary
            long critical = allFindings.stream().filter(f -> f.severity.equals("CRITICAL")).count();
            long high = allFindings.stream().filter(f -> f.severity.equals("HIGH")).count();
            long medium = allFindings.stream().filter(f -> f.severity.equals("MEDIUM")).count();
            long low = allFindings.stream().filter(f -> f.severity.equals("LOW")).count();
            sb.append("Severity Summary: ")
                    .append(critical).append(" CRITICAL, ")
                    .append(high).append(" HIGH, ")
                    .append(medium).append(" MEDIUM, ")
                    .append(low).append(" LOW\n");

            return sb.toString();
        } catch (Exception e) {
            return "Scan failed: " + e.getMessage();
        }
    }

    @Tool(description = "Generate a markdown security audit report from scan results and save it to a file. "
            + "Pass the raw scan output and a file path to save the report.")
    public String generateAuditReport(
            @ToolParam(description = "Raw scan results text (from scanCode tool output)") String scanResults,
            @ToolParam(description = "Full file path to save the markdown report, e.g. C:\\Users\\user\\Desktop\\audit-report.md") String outputPath) {
        notifier.notify("Generating audit report...");
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            StringBuilder md = new StringBuilder();
            md.append("# Security Audit Report\n\n");
            md.append("**Generated:** ").append(timestamp).append("\n\n");
            md.append("---\n\n");

            // Parse the scan results into markdown sections
            String[] sections = scanResults.split("═══ ");
            boolean hasHeader = true;
            for (String section : sections) {
                if (hasHeader) {
                    // First section is the header
                    hasHeader = false;
                    md.append("## Overview\n\n");
                    for (String line : section.split("\n")) {
                        if (!line.isBlank()) md.append("- ").append(line.trim()).append("\n");
                    }
                    md.append("\n");
                    continue;
                }

                // Extract category name and count
                int parenIdx = section.indexOf(" (");
                if (parenIdx > 0) {
                    String category = section.substring(0, parenIdx).trim();
                    md.append("## ").append(category).append("\n\n");
                    md.append("| Severity | File | Line | Issue | Code |\n");
                    md.append("|----------|------|------|-------|------|\n");

                    String[] lines = section.split("\n");
                    for (int i = 1; i < lines.length; i++) {
                        String line = lines[i].trim();
                        if (line.startsWith("[")) {
                            // Parse: [SEVERITY] file:line — description
                            int bracketEnd = line.indexOf("]");
                            String severity = line.substring(1, bracketEnd);
                            String rest = line.substring(bracketEnd + 2);
                            int dashIdx = rest.indexOf(" — ");
                            String fileLine = dashIdx > 0 ? rest.substring(0, dashIdx) : rest;
                            String desc = dashIdx > 0 ? rest.substring(dashIdx + 3) : "";
                            String code = "";
                            if (i + 1 < lines.length && !lines[i + 1].trim().startsWith("[")) {
                                code = lines[i + 1].trim();
                                i++;
                            }
                            md.append("| ").append(severityBadge(severity))
                                    .append(" | `").append(fileLine).append("`")
                                    .append(" | — | ").append(desc)
                                    .append(" | `").append(escapeMarkdown(code)).append("` |\n");
                        }
                    }
                    md.append("\n");
                }
            }

            // Severity summary at end
            if (scanResults.contains("Severity Summary:")) {
                String summary = scanResults.substring(scanResults.indexOf("Severity Summary:"));
                md.append("## Summary\n\n");
                md.append("**").append(summary.trim()).append("**\n\n");
            }

            md.append("---\n\n*Report generated by Mins Bot Code Audit*\n");

            Path outPath = Paths.get(outputPath).toAbsolutePath();
            Files.createDirectories(outPath.getParent());
            Files.writeString(outPath, md.toString(), StandardCharsets.UTF_8);

            return "Audit report saved to: " + outPath;
        } catch (Exception e) {
            return "Failed to generate report: " + e.getMessage();
        }
    }

    @Tool(description = "Clean up a previously cloned repository by deleting its temp folder.")
    public String cleanupClonedRepo(
            @ToolParam(description = "Full path to the cloned repo directory to delete") String repoPath) {
        notifier.notify("Cleaning up " + repoPath + "...");
        try {
            Path dir = Paths.get(repoPath).toAbsolutePath();
            if (!dir.startsWith(TEMP_DIR)) {
                return "Safety check: can only clean up repos under " + TEMP_DIR;
            }
            if (!Files.isDirectory(dir)) return "Not a directory: " + dir;

            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
            return "Cleaned up: " + dir;
        } catch (Exception e) {
            return "Cleanup failed: " + e.getMessage();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═════════════════════════════════════════════════════════════════════════

    private void scanPythonUnusedImports(List<String> lines, String relPath, List<Finding> findings) {
        // Collect all imports
        List<ImportInfo> imports = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = PYTHON_IMPORT.matcher(lines.get(i));
            if (m.matches()) {
                if (m.group(2) != null) {
                    // import X or import X as Y
                    String alias = m.group(3) != null ? m.group(3) : lastPart(m.group(2));
                    imports.add(new ImportInfo(alias, i + 1, lines.get(i).trim()));
                } else if (m.group(4) != null) {
                    // from X import a, b, c
                    for (String name : m.group(4).split(",")) {
                        String trimmed = name.trim();
                        if (trimmed.contains(" as ")) {
                            trimmed = trimmed.substring(trimmed.indexOf(" as ") + 4).trim();
                        }
                        if (!trimmed.equals("*") && !trimmed.isEmpty()) {
                            imports.add(new ImportInfo(trimmed, i + 1, lines.get(i).trim()));
                        }
                    }
                }
            }
        }

        // Check usage in the rest of the file
        String fullText = String.join("\n", lines);
        for (ImportInfo imp : imports) {
            // Count occurrences (excluding the import line itself)
            long count = lines.stream()
                    .filter(line -> !line.trim().startsWith("import ") && !line.trim().startsWith("from "))
                    .filter(line -> line.contains(imp.name))
                    .count();
            if (count == 0) {
                findings.add(new Finding("Unused Import", "LOW",
                        "Unused import: " + imp.name, relPath, imp.line, imp.snippet));
            }
        }
    }

    private static String lastPart(String dotted) {
        int idx = dotted.lastIndexOf('.');
        return idx >= 0 ? dotted.substring(idx + 1) : dotted;
    }

    private String maskSecrets(String line) {
        // Mask anything that looks like a secret value
        return line.replaceAll("([\"'])[A-Za-z0-9_\\-/.]{8,}([\"'])", "$1****$2");
    }

    private String severityBadge(String severity) {
        return switch (severity) {
            case "CRITICAL" -> "**CRITICAL**";
            case "HIGH" -> "**HIGH**";
            case "MEDIUM" -> "MEDIUM";
            default -> "LOW";
        };
    }

    private String escapeMarkdown(String text) {
        return text.replace("|", "\\|").replace("`", "\\`");
    }

    private long countFiles(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile).count();
        }
    }

    // ── Data classes ────────────────────────────────────────────────────────

    private record PatternDef(String name, Pattern pattern, String severity) {}
    private record Finding(String category, String severity, String name,
                           String file, int line, String snippet) {}
    private record ImportInfo(String name, int line, String snippet) {}
}
