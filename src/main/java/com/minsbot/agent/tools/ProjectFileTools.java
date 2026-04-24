package com.minsbot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * Direct file-edit tools scoped to a recorded project. Skip the Claude-Code
 * round-trip for small, deterministic edits (add a route, append a property,
 * write a static HTML page, rename an import). Paths are resolved relative to
 * the project's saved {@code workingDir}; any attempt to escape it via ".."
 * is rejected.
 */
@Component
public class ProjectFileTools {

    private static final Logger log = LoggerFactory.getLogger(ProjectFileTools.class);
    private static final int MAX_READ_CHARS = 20_000;

    private final ProjectHistoryService history;

    public ProjectFileTools(ProjectHistoryService history) {
        this.history = history;
    }

    @Tool(description = "Write (create or overwrite) a file inside a previously-generated project. "
            + "Fast, deterministic — prefer this over continueProject for small/obvious edits "
            + "('add static/index.html to rich-app', 'overwrite application.properties with X'). "
            + "Path is relative to the project folder; `..` is rejected.")
    public String writeProjectFile(
            @ToolParam(description = "Project name or fuzzy match (from listMyProjects)") String projectName,
            @ToolParam(description = "Relative path inside project, e.g. 'src/main/resources/static/index.html'") String relativePath,
            @ToolParam(description = "Full file contents") String content) {
        try {
            Path target = resolve(projectName, relativePath);
            if (target == null) return notFound(projectName, relativePath);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content == null ? "" : content, StandardCharsets.UTF_8);
            log.info("[ProjectFileTools] wrote {} ({} chars)", target, content == null ? 0 : content.length());
            return "Wrote " + target + " (" + (content == null ? 0 : content.length()) + " chars).";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "Append text to a file inside a previously-generated project. "
            + "Use to add a dependency line to pom.xml, a line to application.properties, etc.")
    public String appendProjectFile(
            @ToolParam(description = "Project name") String projectName,
            @ToolParam(description = "Relative path") String relativePath,
            @ToolParam(description = "Text to append (include a leading newline if needed)") String content) {
        try {
            Path target = resolve(projectName, relativePath);
            if (target == null) return notFound(projectName, relativePath);
            Files.createDirectories(target.getParent());
            Files.writeString(target,
                    content == null ? "" : content,
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            return "Appended " + (content == null ? 0 : content.length()) + " chars to " + target + ".";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "Read the first 20k chars of a file inside a previously-generated project. "
            + "Use to inspect pom.xml, application.properties, a specific source file, etc.")
    public String readProjectFile(
            @ToolParam(description = "Project name") String projectName,
            @ToolParam(description = "Relative path") String relativePath) {
        try {
            Path target = resolve(projectName, relativePath);
            if (target == null) return notFound(projectName, relativePath);
            if (!Files.exists(target)) return "File does not exist: " + target;
            String body = Files.readString(target, StandardCharsets.UTF_8);
            if (body.length() > MAX_READ_CHARS) body = body.substring(0, MAX_READ_CHARS) + "\n... (truncated)";
            return "── " + target + " ──\n" + body;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "Replace the first occurrence of a literal string in a project file. "
            + "For precise one-off edits. If the find-string doesn't match, the file is NOT modified.")
    public String editProjectFile(
            @ToolParam(description = "Project name") String projectName,
            @ToolParam(description = "Relative path") String relativePath,
            @ToolParam(description = "Literal text to find (must match exactly)") String find,
            @ToolParam(description = "Replacement text") String replace) {
        try {
            Path target = resolve(projectName, relativePath);
            if (target == null) return notFound(projectName, relativePath);
            if (!Files.exists(target)) return "File does not exist: " + target;
            if (find == null || find.isEmpty()) return "Error: find string is empty.";
            String body = Files.readString(target, StandardCharsets.UTF_8);
            int idx = body.indexOf(find);
            if (idx < 0) return "No match for the find-string in " + target + " — file unchanged.";
            String updated = body.substring(0, idx) + (replace == null ? "" : replace) + body.substring(idx + find.length());
            Files.writeString(target, updated, StandardCharsets.UTF_8);
            return "Replaced 1 occurrence in " + target + ".";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "List files inside a project folder (or a subfolder). "
            + "Skips build/IDE metadata (target, build, node_modules, .git, etc.).")
    public String listProjectFiles(
            @ToolParam(description = "Project name") String projectName,
            @ToolParam(description = "Subpath inside project ('' for root)") String subpath) {
        try {
            Path target = resolve(projectName, subpath == null || subpath.isBlank() ? "." : subpath);
            if (target == null) return notFound(projectName, subpath);
            if (!Files.isDirectory(target)) return "Not a directory: " + target;
            java.util.Set<String> skipDirs = java.util.Set.of(
                    ".git", "target", "build", "dist", "out", "node_modules",
                    ".gradle", ".idea", ".vscode", "__pycache__", "venv");
            try (var s = Files.walk(target)) {
                List<String> rels = s.filter(Files::isRegularFile)
                        .filter(pp -> {
                            for (Path part : target.relativize(pp)) {
                                if (skipDirs.contains(part.toString())) return false;
                            }
                            return true;
                        })
                        .map(pp -> target.relativize(pp).toString().replace('\\', '/'))
                        .sorted(Comparator.naturalOrder())
                        .limit(200)
                        .toList();
                if (rels.isEmpty()) return "(empty)";
                return "Files under " + target + " (" + rels.size() + "):\n  " + String.join("\n  ", rels);
            }
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // ─── helpers ──────────────────────────────────────────────────

    /** Resolve {@code relativePath} inside the project's recorded workingDir. Null if project unknown or path escapes. */
    private Path resolve(String projectName, String relativePath) {
        ProjectHistoryService.ProjectRecord rec = history.findByName(projectName);
        if (rec == null || rec.workingDir == null) return null;
        Path dir = Path.of(rec.workingDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) return null;
        String rel = relativePath == null ? "." : relativePath.replace('\\', '/').trim();
        Path candidate = dir.resolve(rel).toAbsolutePath().normalize();
        if (!candidate.startsWith(dir)) return null; // path traversal rejected
        return candidate;
    }

    private String notFound(String projectName, String relativePath) {
        return "No project matching '" + projectName + "' in history, "
                + "or the path '" + relativePath + "' escapes the project folder. "
                + "Call listMyProjects to see available names.";
    }
}
