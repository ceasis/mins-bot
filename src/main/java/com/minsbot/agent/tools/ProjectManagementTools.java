package com.minsbot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Project lifecycle tools: import from git, copy, add dependency, check for
 * dependency updates, package/build artifact, delete.
 *
 * <p>Deliberately kept in one file to avoid class-sprawl. Each method is
 * independent and short; they all operate on the project folder recorded in
 * {@link ProjectHistoryService}.</p>
 */
@Component
public class ProjectManagementTools {

    private static final Logger log = LoggerFactory.getLogger(ProjectManagementTools.class);

    private final ProjectHistoryService history;
    private final ToolExecutionNotifier notifier;
    private final ProjectVerifyService verify;

    public ProjectManagementTools(ProjectHistoryService history,
                                  ToolExecutionNotifier notifier,
                                  ProjectVerifyService verify) {
        this.history = history;
        this.notifier = notifier;
        this.verify = verify;
    }

    // ═══════════════════════════════════════════════════════════════════
    // #3 — Import an existing project from a git URL
    // ═══════════════════════════════════════════════════════════════════

    @Tool(description = "Clone an existing git repository and register it in project history so "
            + "it can be referenced by name in continueProject, buildAndLaunch, etc. "
            + "Use when the user says 'import repo X', 'clone Y and set it up', 'bring repo Z into scope'.")
    public String importProject(
            @ToolParam(description = "Git URL (https://... or git@...)") String gitUrl,
            @ToolParam(description = "Absolute base directory where the repo should be cloned") String baseDir,
            @ToolParam(description = "Optional project name (folder name) — leave empty to derive from URL") String projectName) {
        try {
            if (gitUrl == null || gitUrl.isBlank()) return "Error: gitUrl is required.";
            if (baseDir == null || baseDir.isBlank()) return "Error: baseDir is required.";

            String name = (projectName == null || projectName.isBlank())
                    ? deriveNameFromGitUrl(gitUrl) : projectName.trim();
            name = name.replaceAll("[^a-zA-Z0-9_-]", "-");
            Path dest = Path.of(baseDir, name);
            if (Files.exists(dest)) return "Destination already exists: " + dest + ". Pick a different name or delete first.";

            notifier.notify("Cloning " + gitUrl + "...");
            Result r = run(Path.of(baseDir), "git", "clone", gitUrl, name);
            if (r.exit != 0) return "git clone failed (exit " + r.exit + "):\n" + tail(r.output, 30);
            log.info("[Import] Cloned {} → {}", gitUrl, dest);

            history.record(null, "imported", "git clone " + gitUrl, dest.toString(), "done",
                    "Imported from " + gitUrl);

            // Best-effort verify so the user knows the build status of what they just pulled.
            String v = verify.verifyProject(dest.toString());
            String vSummary = v == null ? "(not verified)"
                    : v.lines().findFirst().orElse("(not verified)");
            return "✓ Imported " + name + " at " + dest + "\nVerify: " + vSummary;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private static String deriveNameFromGitUrl(String url) {
        String s = url.trim();
        if (s.endsWith(".git")) s = s.substring(0, s.length() - 4);
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf(':'));
        return slash >= 0 && slash < s.length() - 1 ? s.substring(slash + 1) : "imported-project";
    }

    // ═══════════════════════════════════════════════════════════════════
    // #6 — Duplicate a project locally
    // ═══════════════════════════════════════════════════════════════════

    @Tool(description = "Duplicate an existing project folder under a new name. "
            + "Use for 'clone rich-app as rich-app-v2', 'fork my project locally'. "
            + "Copies everything except .git, target, build, node_modules so the copy stays clean.")
    public String cloneProjectLocally(
            @ToolParam(description = "Source project name") String sourceName,
            @ToolParam(description = "New folder name") String targetName,
            @ToolParam(description = "Absolute base directory for the new folder "
                    + "(empty = same parent as source)") String baseDir) {
        try {
            ProjectHistoryService.ProjectRecord rec = history.findByName(sourceName);
            if (rec == null || rec.workingDir == null) return "Unknown source project: " + sourceName;
            Path src = Path.of(rec.workingDir);
            if (!Files.isDirectory(src)) return "Source folder missing: " + src;

            String safe = targetName.replaceAll("[^a-zA-Z0-9_-]", "-");
            Path parent = (baseDir == null || baseDir.isBlank()) ? src.getParent() : Path.of(baseDir);
            if (parent == null) return "Cannot resolve parent dir for target.";
            Path dest = parent.resolve(safe);
            if (Files.exists(dest)) return "Destination already exists: " + dest;

            notifier.notify("Cloning " + sourceName + " → " + safe + "...");
            copyTree(src, dest);
            log.info("[CloneLocal] {} → {}", src, dest);
            history.record(null, "cloned", "cloned from " + sourceName, dest.toString(),
                    "done", "Local copy of " + sourceName);
            return "✓ Cloned " + sourceName + " → " + dest;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private static final java.util.Set<String> COPY_SKIP = java.util.Set.of(
            ".git", "target", "build", "dist", "out", "node_modules",
            ".gradle", ".idea", ".vscode", "__pycache__", "venv", ".venv");

    private void copyTree(Path src, Path dest) throws java.io.IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws java.io.IOException {
                if (!src.equals(dir) && COPY_SKIP.contains(dir.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(dest.resolve(src.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws java.io.IOException {
                Files.copy(file, dest.resolve(src.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════
    // #4 — Add a dependency
    // ═══════════════════════════════════════════════════════════════════

    @Tool(description = "Add a Maven dependency to a project's pom.xml. "
            + "Use for 'add lombok to rich-app', 'add spring-boot-starter-security'. "
            + "Inserts before </dependencies>; no-op if the artifactId already exists.")
    public String addMavenDependency(
            @ToolParam(description = "Project name") String projectName,
            @ToolParam(description = "Maven groupId, e.g. 'org.projectlombok'") String groupId,
            @ToolParam(description = "Maven artifactId, e.g. 'lombok'") String artifactId,
            @ToolParam(description = "Version (empty string = let Spring Boot parent manage it)") String version) {
        try {
            Path pom = resolveProjectFile(projectName, "pom.xml");
            if (pom == null) return "No pom.xml for project '" + projectName + "'.";
            String body = Files.readString(pom, StandardCharsets.UTF_8);
            if (body.contains("<artifactId>" + artifactId + "</artifactId>")) {
                return artifactId + " already present in pom.xml — no change.";
            }
            int closeIdx = body.lastIndexOf("</dependencies>");
            if (closeIdx < 0) return "Could not find </dependencies> in pom.xml — not modified.";
            String dep = "    <dependency>\n"
                    + "      <groupId>" + groupId + "</groupId>\n"
                    + "      <artifactId>" + artifactId + "</artifactId>\n"
                    + (version == null || version.isBlank() ? "" : "      <version>" + version + "</version>\n")
                    + "    </dependency>\n  ";
            String updated = body.substring(0, closeIdx) + dep + body.substring(closeIdx);
            Files.writeString(pom, updated, StandardCharsets.UTF_8);
            log.info("[AddDep] maven {}:{}:{} → {}", groupId, artifactId, version, pom);
            notifier.notify("Added " + groupId + ":" + artifactId + " to pom.xml");
            return "✓ Added " + groupId + ":" + artifactId
                    + (version == null || version.isBlank() ? "" : ":" + version)
                    + " to " + pom;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "Add an npm dependency to a project's package.json (under dependencies). "
            + "Use for 'add axios to my-app', 'add lodash to frontend'. "
            + "Does NOT run npm install — call npm install via runPowerShell after.")
    public String addNpmDependency(
            @ToolParam(description = "Project name") String projectName,
            @ToolParam(description = "Package name, e.g. 'axios'") String packageName,
            @ToolParam(description = "Version spec, e.g. '^1.6.0' (empty = 'latest')") String version) {
        try {
            Path pj = resolveProjectFile(projectName, "package.json");
            if (pj == null) return "No package.json for project '" + projectName + "'.";
            String body = Files.readString(pj, StandardCharsets.UTF_8);
            String v = (version == null || version.isBlank()) ? "latest" : version;

            // Tolerant string edit: ensure a "dependencies": { ... } block exists with our entry.
            if (body.contains("\"" + packageName + "\"")) {
                return packageName + " already present in package.json — no change.";
            }
            String needle = "\"dependencies\"";
            int depIdx = body.indexOf(needle);
            String updated;
            if (depIdx < 0) {
                // No deps block — insert one right before closing brace.
                int lastBrace = body.lastIndexOf('}');
                if (lastBrace < 0) return "package.json has no closing brace — not modified.";
                String sep = body.substring(0, lastBrace).trim().endsWith(",") ? "" : ",";
                String block = sep + "\n  \"dependencies\": { \"" + packageName + "\": \"" + v + "\" }\n";
                updated = body.substring(0, lastBrace) + block + body.substring(lastBrace);
            } else {
                int openBrace = body.indexOf('{', depIdx);
                if (openBrace < 0) return "Malformed dependencies block.";
                String entry = "    \"" + packageName + "\": \"" + v + "\",\n";
                updated = body.substring(0, openBrace + 1) + "\n" + entry + body.substring(openBrace + 1);
            }
            Files.writeString(pj, updated, StandardCharsets.UTF_8);
            log.info("[AddDep] npm {}@{} → {}", packageName, v, pj);
            notifier.notify("Added " + packageName + "@" + v + " to package.json");
            return "✓ Added " + packageName + "@" + v + " to " + pj + ". Run `npm install` to fetch it.";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // #7 — Check for dependency updates
    // ═══════════════════════════════════════════════════════════════════

    @Tool(description = "Report available dependency updates for a project. "
            + "Uses `mvn versions:display-dependency-updates -U` for Maven or `npm outdated` for Node. "
            + "Read-only — does NOT apply any upgrades.")
    public String checkDependencyUpdates(
            @ToolParam(description = "Project name") String projectName) {
        try {
            ProjectHistoryService.ProjectRecord rec = history.findByName(projectName);
            if (rec == null) return "Unknown project: " + projectName;
            Path dir = Path.of(rec.workingDir);
            if (!Files.isDirectory(dir)) return "Folder missing: " + dir;

            if (Files.exists(dir.resolve("pom.xml"))) {
                notifier.notify("mvn versions:display-dependency-updates...");
                Result r = run(dir, "mvn", "-q", "versions:display-dependency-updates");
                return "Maven updates:\n" + tail(r.output, 60);
            }
            if (Files.exists(dir.resolve("package.json"))) {
                notifier.notify("npm outdated...");
                Result r = run(dir, "npm", "outdated");
                // npm outdated exits 1 when anything is outdated — not an error here.
                return "npm outdated:\n" + tail(r.output, 60);
            }
            return "No Maven or npm project detected in " + dir;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // #9 — Package the project
    // ═══════════════════════════════════════════════════════════════════

    @Tool(description = "Build a distributable artifact: `mvn package` for Maven, `npm run build` for Node. "
            + "Returns the path to the produced file on success or the error tail on failure.")
    public String packageProject(
            @ToolParam(description = "Project name") String projectName) {
        try {
            ProjectHistoryService.ProjectRecord rec = history.findByName(projectName);
            if (rec == null) return "Unknown project: " + projectName;
            Path dir = Path.of(rec.workingDir);
            if (!Files.isDirectory(dir)) return "Folder missing: " + dir;

            if (Files.exists(dir.resolve("pom.xml"))) {
                notifier.notify("mvn package -DskipTests...");
                Result r = run(dir, "mvn", "-q", "-DskipTests", "package");
                if (r.exit != 0) return "✗ Package failed (mvn):\n" + tail(r.output, 60);
                // Find the jar.
                Path target = dir.resolve("target");
                if (Files.isDirectory(target)) {
                    try (var s = Files.list(target)) {
                        String jar = s.filter(p -> p.toString().endsWith(".jar"))
                                .filter(p -> !p.toString().endsWith("-sources.jar"))
                                .map(Path::toString).findFirst().orElse(null);
                        if (jar != null) return "✓ Packaged: " + jar;
                    }
                }
                return "✓ mvn package succeeded. Look under " + target + " for the jar.";
            }
            if (Files.exists(dir.resolve("package.json"))) {
                notifier.notify("npm run build...");
                Result r = run(dir, "npm", "run", "build");
                if (r.exit != 0) return "✗ Build failed (npm):\n" + tail(r.output, 60);
                return "✓ npm run build succeeded. Output usually in dist/ or build/.";
            }
            return "No Maven or npm project detected in " + dir;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // #10 — Delete a project (safe)
    // ═══════════════════════════════════════════════════════════════════

    @Tool(description = "Delete a project folder AND remove it from history. Destructive. "
            + "Returns what would be deleted in a dry-run by default; pass confirm=true to actually delete. "
            + "Does NOT touch the remote GitHub repo — delete that manually with `gh repo delete`.")
    public String deleteProject(
            @ToolParam(description = "Project name") String projectName,
            @ToolParam(description = "Must be true to perform the deletion (otherwise dry-run only)") boolean confirm) {
        try {
            ProjectHistoryService.ProjectRecord rec = history.findByName(projectName);
            if (rec == null) return "Unknown project: " + projectName;
            Path dir = Path.of(rec.workingDir);

            if (!confirm) {
                long size = -1, count = -1;
                if (Files.isDirectory(dir)) {
                    try (var s = Files.walk(dir)) {
                        var files = s.filter(Files::isRegularFile).toList();
                        count = files.size();
                        size = files.stream().mapToLong(p -> {
                            try { return Files.size(p); } catch (Exception e) { return 0; }
                        }).sum();
                    }
                }
                return "[DRY RUN] Would delete:\n"
                        + "  project: " + rec.projectName + "\n"
                        + "  folder:  " + dir + (Files.isDirectory(dir) ? "" : " (missing)") + "\n"
                        + "  files:   " + count + "\n"
                        + "  size:    " + (size / 1024) + " KB\n"
                        + "  github:  " + (rec.githubUrl == null ? "(none tracked)" : rec.githubUrl + " — NOT deleted (use gh repo delete)") + "\n"
                        + "Call again with confirm=true to proceed.";
            }

            // Actually delete.
            if (Files.isDirectory(dir)) {
                notifier.notify("Deleting " + dir + "...");
                deleteTree(dir);
            }
            // Remove from history by recording a tombstone record that findByName won't match
            // (name cleared). The cleanest path is to rewrite the file without this entry; we use
            // a no-op marker since ProjectHistoryService dedupes by workingDir.
            try {
                java.lang.reflect.Field recordsField = ProjectHistoryService.class.getDeclaredField("records");
                recordsField.setAccessible(true);
                @SuppressWarnings("unchecked")
                var recs = (java.util.concurrent.CopyOnWriteArrayList<ProjectHistoryService.ProjectRecord>) recordsField.get(history);
                recs.removeIf(r -> rec.workingDir != null && rec.workingDir.equals(r.workingDir));
                java.lang.reflect.Method persist = ProjectHistoryService.class.getDeclaredMethod("persist");
                persist.setAccessible(true);
                persist.invoke(history);
            } catch (Exception reflectErr) {
                log.warn("[Delete] History cleanup reflection failed: {}", reflectErr.getMessage());
            }
            return "✓ Deleted " + rec.projectName + " at " + dir
                    + (rec.githubUrl != null ? "\nReminder: the GitHub repo " + rec.githubUrl
                        + " was NOT deleted — run `gh repo delete " + rec.githubUrl + " --yes` manually." : "");
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private void deleteTree(Path dir) throws java.io.IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws java.io.IOException {
                Files.deleteIfExists(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path d, java.io.IOException exc) throws java.io.IOException {
                Files.deleteIfExists(d); return FileVisitResult.CONTINUE;
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════
    // helpers
    // ═══════════════════════════════════════════════════════════════════

    private Path resolveProjectFile(String projectName, String relPath) {
        ProjectHistoryService.ProjectRecord rec = history.findByName(projectName);
        if (rec == null || rec.workingDir == null) return null;
        Path p = Path.of(rec.workingDir, relPath);
        return Files.exists(p) ? p : null;
    }

    private Result run(Path dir, String... cmd) throws Exception {
        List<String> list = new ArrayList<>();
        for (String c : cmd) list.add(c);
        ProcessBuilder pb = new ProcessBuilder(list).directory(dir.toFile()).redirectErrorStream(true);
        Process p = pb.start();
        byte[] bytes = p.getInputStream().readAllBytes();
        boolean done = p.waitFor(5, TimeUnit.MINUTES);
        if (!done) { p.destroyForcibly(); return new Result(-1, "timed out"); }
        return new Result(p.exitValue(), new String(bytes, StandardCharsets.UTF_8));
    }

    private static String tail(String s, int lines) {
        if (s == null || s.isBlank()) return "(no output)";
        String[] all = s.split("\\R");
        int from = Math.max(0, all.length - lines);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < all.length; i++) sb.append(all[i]).append('\n');
        return sb.toString().stripTrailing();
    }

    /** Silences "unused" warning; Instant is used by the history service, reference kept for clarity. */
    @SuppressWarnings("unused")
    private static Instant now() { return Instant.now(); }

    private record Result(int exit, String output) {}
}
