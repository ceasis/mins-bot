package com.minsbot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Auto-detect the project type and run a fast compile/typecheck so we catch
 * Claude's occasional hallucinated-but-syntactically-broken code before the
 * user tries to run it.
 *
 * <ul>
 *   <li>pom.xml  → {@code mvn -q -DskipTests compile}</li>
 *   <li>build.gradle(.kts)  → {@code ./gradlew compileJava -q}</li>
 *   <li>package.json + tsconfig.json → {@code npx tsc --noEmit}</li>
 *   <li>package.json only → {@code node --check} on main JS file, best-effort</li>
 *   <li>requirements.txt / *.py → {@code python -m py_compile} on sources</li>
 * </ul>
 */
@Component
public class ProjectVerifyService {

    private static final Logger log = LoggerFactory.getLogger(ProjectVerifyService.class);
    private static final int COMPILE_TIMEOUT_SEC = 180;

    private final ToolExecutionNotifier notifier;

    public ProjectVerifyService(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @Tool(description = "Compile/typecheck a project and return any errors. Use after scaffolding or "
            + "modifying code to catch issues before running the app. Auto-detects Maven, Gradle, "
            + "TypeScript, Node, or Python based on project files. Returns 'PASS' or a trimmed "
            + "error tail on failure.")
    public String verifyProject(
            @ToolParam(description = "Absolute project directory") String workingDir) {
        try {
            if (workingDir == null || workingDir.isBlank()) return "Error: workingDir is required.";
            Path dir = Path.of(workingDir);
            if (!Files.isDirectory(dir)) return "Folder does not exist: " + workingDir;

            String kind = detectKind(dir);
            notifier.notify("Verifying " + kind + " project...");
            log.info("[Verify] kind={} dir={}", kind, dir);

            Result r;
            switch (kind) {
                case "maven":       r = run(dir, "mvn", "-q", "-DskipTests", "compile"); break;
                case "gradle":      r = runGradle(dir); break;
                case "typescript":  r = run(dir, "npx", "--yes", "tsc", "--noEmit"); break;
                case "python":      r = verifyPython(dir); break;
                case "node":        r = verifyNodeSyntax(dir); break;
                default:            return "No known build system detected — skipping verify.";
            }

            if (r.exit == 0) {
                notifier.notify("✓ Verify passed (" + kind + ").");
                return "PASS (" + kind + ").";
            }
            String trimmed = tailLines(r.output, 60);
            notifier.notify("✗ Verify failed (" + kind + ") — see errors.");
            return "FAIL (" + kind + ", exit=" + r.exit + "):\n" + trimmed;
        } catch (Exception e) {
            return "Verify error: " + e.getMessage();
        }
    }

    /** Same as verifyProject but looks up project by name via history. */
    public String verifyByName(String projectName, ProjectHistoryService history) {
        ProjectHistoryService.ProjectRecord rec = history.findByName(projectName);
        if (rec == null || rec.workingDir == null) return "No project '" + projectName + "' found in history.";
        return verifyProject(rec.workingDir);
    }

    // ─── detection ────────────────────────────────────────────────

    private String detectKind(Path dir) {
        if (Files.exists(dir.resolve("pom.xml"))) return "maven";
        if (Files.exists(dir.resolve("build.gradle")) || Files.exists(dir.resolve("build.gradle.kts"))) return "gradle";
        if (Files.exists(dir.resolve("tsconfig.json"))) return "typescript";
        if (Files.exists(dir.resolve("package.json"))) return "node";
        if (Files.exists(dir.resolve("requirements.txt")) || Files.exists(dir.resolve("pyproject.toml"))) return "python";
        // Any .py files? Treat as python.
        try (var s = Files.walk(dir, 3)) {
            if (s.anyMatch(p -> p.toString().endsWith(".py"))) return "python";
        } catch (Exception ignored) {}
        return "unknown";
    }

    // ─── runners ──────────────────────────────────────────────────

    private Result runGradle(Path dir) throws Exception {
        Path wrapper = dir.resolve("gradlew.bat");
        if (Files.exists(wrapper)) return run(dir, wrapper.toString(), "compileJava", "-q");
        return run(dir, "gradle", "compileJava", "-q");
    }

    private Result verifyPython(Path dir) throws Exception {
        // py_compile on every .py file; aggregate exit codes.
        try (var s = Files.walk(dir)) {
            var files = s.filter(p -> p.toString().endsWith(".py"))
                    .filter(p -> !p.toString().contains("venv"))
                    .filter(p -> !p.toString().contains("__pycache__"))
                    .limit(200)
                    .toList();
            if (files.isEmpty()) return new Result(0, "(no .py files)");
            StringBuilder out = new StringBuilder();
            int worstExit = 0;
            for (Path f : files) {
                Result r = run(dir, "python", "-m", "py_compile", f.toString());
                if (r.exit != 0) {
                    worstExit = r.exit;
                    out.append(dir.relativize(f)).append(":\n").append(r.output).append("\n");
                }
            }
            return new Result(worstExit, worstExit == 0 ? "all .py files compile cleanly" : out.toString());
        }
    }

    private Result verifyNodeSyntax(Path dir) throws Exception {
        // Best-effort: node --check on main if declared.
        Path pkg = dir.resolve("package.json");
        if (!Files.exists(pkg)) return new Result(0, "(no package.json)");
        String body = Files.readString(pkg, StandardCharsets.UTF_8);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\"main\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        if (m.find()) {
            Path main = dir.resolve(m.group(1));
            if (Files.exists(main)) return run(dir, "node", "--check", main.toString());
        }
        return new Result(0, "(no tsconfig.json; skipping deep typecheck)");
    }

    private Result run(Path dir, String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        Process p = pb.start();
        byte[] bytes = p.getInputStream().readAllBytes();
        boolean done = p.waitFor(COMPILE_TIMEOUT_SEC, TimeUnit.SECONDS);
        if (!done) { p.destroyForcibly(); return new Result(-1, "timed out after " + COMPILE_TIMEOUT_SEC + "s"); }
        return new Result(p.exitValue(), new String(bytes, StandardCharsets.UTF_8));
    }

    private static String tailLines(String s, int lines) {
        if (s == null || s.isBlank()) return "(no output)";
        String[] all = s.split("\\R");
        int from = Math.max(0, all.length - lines);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < all.length; i++) sb.append(all[i]).append('\n');
        return sb.toString().stripTrailing();
    }

    private record Result(int exit, String output) {}
}
