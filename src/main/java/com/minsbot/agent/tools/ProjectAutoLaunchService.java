package com.minsbot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One-shot "make it compile, then make it run" loop. Chains verify →
 * (fix-via-Claude if needed) → kill port → start → (fix-via-Claude if the
 * server exited early). Caps attempts to keep runtime bounded.
 *
 * <p>This is what the LLM should call when the user says
 * "scaffold X, make sure it runs", "get rich-app working", or
 * "fix whatever's broken and launch it".</p>
 */
@Component
public class ProjectAutoLaunchService {

    private static final Logger log = LoggerFactory.getLogger(ProjectAutoLaunchService.class);

    private final ProjectHistoryService history;
    private final ProjectVerifyService verify;
    private final DevServerTools devServerTools;
    private final ClaudeCodeTools claudeCodeTools;
    private final ToolExecutionNotifier notifier;
    private final ProjectTemplateTools templates;
    private final ProjectTestService testService;

    public ProjectAutoLaunchService(ProjectHistoryService history,
                                    ProjectVerifyService verify,
                                    DevServerTools devServerTools,
                                    ClaudeCodeTools claudeCodeTools,
                                    ToolExecutionNotifier notifier,
                                    ProjectTemplateTools templates,
                                    ProjectTestService testService) {
        this.history = history;
        this.verify = verify;
        this.devServerTools = devServerTools;
        this.claudeCodeTools = claudeCodeTools;
        this.notifier = notifier;
        this.templates = templates;
        this.testService = testService;
    }

    /** Default port per template preset — used when the user doesn't specify one. */
    private static int defaultPortFor(String templateId) {
        if (templateId == null) return 8080;
        switch (templateId.toLowerCase()) {
            case "react-vite":
            case "vite-react":        return 5173;
            case "fastapi":
            case "python-fastapi":    return 8000;
            case "node-express":
            case "express":           return 3000;
            case "spring-boot-tailwind":
            case "spring-boot-jpa":
            default:                  return 8080;
        }
    }

    @Tool(description = "ONE-SHOT: scaffold a project from a template AND build/launch it. "
            + "Use this for any 'generate me an app', 'make me a spring boot app', 'create a "
            + "react app and run it' request where the user wants a working app end-to-end. "
            + "Internally: createFromTemplate → buildAndLaunch(3 retries). Opens the browser on success. "
            + "If you're unsure which template, default to spring-boot-tailwind for Java, react-vite for React, "
            + "fastapi for Python, node-express for Node/JS.")
    public String scaffoldAndLaunch(
            @ToolParam(description = "Template id: spring-boot-tailwind | spring-boot-jpa | react-vite | fastapi | node-express") String templateId,
            @ToolParam(description = "Project name (folder name + artifact id)") String projectName,
            @ToolParam(description = "Absolute base directory, e.g. C:\\Users\\cholo\\eclipse-workspace") String baseDir,
            @ToolParam(description = "Port to run on; 0 = use template default (8080 for Spring, 5173 Vite, 8000 FastAPI, 3000 Express)") int port,
            @ToolParam(description = "Also git-init and push to GitHub") boolean createGithub,
            @ToolParam(description = "Make the GitHub repo PRIVATE (default true; pass false only for explicit public request)") boolean isPrivate) {
        try {
            notifier.notify("Phase 1: scaffolding from template '" + templateId + "'...");
            String scaffoldOut = templates.createFromTemplate(templateId, projectName, baseDir, createGithub, isPrivate);
            if (scaffoldOut == null || scaffoldOut.startsWith("Error") || scaffoldOut.startsWith("Unknown template")) {
                return "✗ Scaffold failed:\n" + scaffoldOut;
            }

            // After createFromTemplate, the project is recorded in history by its name.
            // The safe scaffolded folder name may differ (non-alphanumerics replaced). Look up
            // by the projectName string; fuzzy match will catch variants.
            int effectivePort = port > 0 ? port : defaultPortFor(templateId);
            notifier.notify("Phase 2: build-and-launch on port " + effectivePort + "...");
            String launchOut = buildAndLaunch(projectName, effectivePort, 3);

            StringBuilder result = new StringBuilder();
            result.append(scaffoldOut).append("\n\n── build & launch ──\n").append(launchOut);

            // Phase 3: auto-QA if the server actually launched.
            if (launchOut != null && launchOut.contains("✓ Launched")) {
                notifier.notify("Phase 3: running QA (API sweep, UI probe, log scan)...");
                try {
                    String qa = testService.testProject(projectName, effectivePort);
                    result.append("\n\n── QA ──\n").append(qa);
                } catch (Exception qaEx) {
                    result.append("\n\n── QA skipped: ").append(qaEx.getMessage()).append(" ──");
                }
            }
            return result.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "Build-verify-launch-fix loop. Use when the user says 'make it run', "
            + "'get <project> working', 'launch rich-app and fix any errors'. "
            + "Runs: verifyProject → (if fail, ask Claude to fix and retry) → "
            + "killProcessOnPort → startDevServer → (if server died, read run.err, "
            + "ask Claude to fix, retry). Stops at 3 attempts. Returns a final state report. "
            + "Prefer this over chaining verify+start+readLog manually.")
    public String buildAndLaunch(
            @ToolParam(description = "Project name (resolved via listMyProjects)") String projectName,
            @ToolParam(description = "Port to bind the server on, 0 = use project default") int port,
            @ToolParam(description = "Max fix attempts (recommended 3; minimum 1, max 5)") int maxAttempts) {
        ProjectHistoryService.ProjectRecord rec = history.findByName(projectName);
        if (rec == null || rec.workingDir == null) {
            return "No project matching '" + projectName + "'. Call listMyProjects first.";
        }
        int attempts = Math.max(1, Math.min(5, maxAttempts == 0 ? 3 : maxAttempts));
        Path dir = Path.of(rec.workingDir);
        if (!Files.isDirectory(dir)) return "Folder does not exist: " + rec.workingDir;

        StringBuilder report = new StringBuilder();
        report.append("── buildAndLaunch '").append(rec.projectName).append("' ──\n");

        // ─── Phase 1: verify compile, ask Claude to fix if needed ───
        notifier.notify("Phase 1: verify compile...");
        String verifyResult = loopVerifyAndFix(dir, attempts, report);
        if (!verifyResult.startsWith("PASS")) {
            return report.append("\n✗ Verify failed after ").append(attempts)
                    .append(" attempt(s):\n").append(tail(verifyResult, 30)).toString();
        }

        // ─── Phase 2: free the port if needed ───
        if (port > 0) {
            notifier.notify("Phase 2: freeing port " + port + "...");
            String kill = devServerTools.killProcessOnPort(port);
            if (kill != null && !kill.isBlank()) {
                report.append("Port ").append(port).append(": ")
                      .append(kill.lines().findFirst().orElse("(no output)")).append('\n');
            }
        }

        // ─── Phase 3: start the server, fix on exit-early ───
        notifier.notify("Phase 3: starting dev server...");
        String startResult = loopStartAndFix(dir, port, attempts, report);
        report.append('\n').append(startResult);
        return report.toString();
    }

    private String loopVerifyAndFix(Path dir, int maxAttempts, StringBuilder report) {
        String last = "";
        for (int i = 1; i <= maxAttempts; i++) {
            last = verify.verifyProject(dir.toString());
            if (last == null) last = "";
            report.append("  Verify attempt ").append(i).append(": ")
                  .append(last.lines().findFirst().orElse("")).append('\n');
            if (last.startsWith("PASS")) return last;
            if (last.startsWith("No known build system")) return "PASS (skipped)";
            if (i == maxAttempts) break;
            notifier.notify("Compile failed — asking Claude to fix (attempt " + i + "/" + (maxAttempts - 1) + ")...");
            String fixPrompt = "The build is FAILING. Read the error tail below and MAKE THE MINIMAL CHANGES to fix it.\n"
                    + "Do NOT scaffold a new project. Do NOT rewrite unrelated files. Do NOT punt with a summary.\n"
                    + "You MUST edit the existing source to make it compile. After your changes, stay silent.\n\n"
                    + "── error tail ──\n" + tail(last, 40);
            claudeCodeTools.run(fixPrompt, dir.toString(), false, false);
        }
        return last;
    }

    private String loopStartAndFix(Path dir, int port, int maxAttempts, StringBuilder report) {
        String preset = detectPreset(dir);
        for (int i = 1; i <= maxAttempts; i++) {
            String startOut = devServerTools.startDevServer(dir.toString(), preset, port);
            boolean running = startOut != null && startOut.startsWith("✓ Server running");
            report.append("  Start attempt ").append(i).append(": ")
                  .append(startOut == null ? "(null)" : startOut.lines().findFirst().orElse(""))
                  .append('\n');
            if (running) {
                // Auto-open the root URL in the default browser so the user doesn't have to.
                // Only open if startDevServer reported GET / returning HTTP 200, OR a specific port was given
                // (then we trust the port and open anyway — a 404 at root still tells the user where the app is).
                if (port > 0) {
                    try {
                        String url = "http://localhost:" + port + "/";
                        new ProcessBuilder("powershell.exe", "-NoProfile", "-Command",
                                "Start-Process '" + url + "'")
                                .redirectErrorStream(true).start();
                        notifier.notify("🌐 Opening " + url + " in your browser...");
                    } catch (Exception ignored) {}
                }
                return "✓ Launched on attempt " + i + "\n\n" + startOut;
            }
            if (i == maxAttempts) return "✗ Failed to launch after " + maxAttempts + " attempts\n\n" + startOut;
            // Pull the error tail and ask Claude to fix.
            String errTail = devServerTools.readProjectDevLog(dir.toString(), "stderr", 40);
            if (errTail == null || errTail.isBlank() || errTail.contains("missing or empty")) {
                errTail = devServerTools.readProjectDevLog(dir.toString(), "stdout", 40);
            }
            notifier.notify("Server exited — asking Claude to fix (attempt " + i + "/" + (maxAttempts - 1) + ")...");
            String fixPrompt = "The dev server FAILED TO START or exited early. Diagnose from the error tail "
                    + "below and make the MINIMAL fix to get it running. Do NOT scaffold new files. "
                    + "Do NOT punt with a summary. Edit existing files until the error is gone.\n\n"
                    + "── error tail ──\n" + tail(errTail, 60);
            claudeCodeTools.run(fixPrompt, dir.toString(), false, false);
        }
        return "✗ Unexpected loop exit";
    }

    private String detectPreset(Path dir) {
        if (Files.exists(dir.resolve("pom.xml"))) return "spring-boot";
        if (Files.exists(dir.resolve("build.gradle")) || Files.exists(dir.resolve("build.gradle.kts"))) return "gradle-boot";
        if (Files.exists(dir.resolve("vite.config.js")) || Files.exists(dir.resolve("vite.config.ts"))) return "vite";
        if (Files.exists(dir.resolve("package.json"))) {
            try {
                String body = Files.readString(dir.resolve("package.json"), StandardCharsets.UTF_8);
                if (body.contains("\"dev\"")) return "npm-dev";
                if (body.contains("\"start\"")) return "npm-start";
            } catch (Exception ignored) {}
            return "npm-start";
        }
        if (Files.exists(dir.resolve("app.py"))) return "python-app";
        return "spring-boot"; // reasonable default — will fail loudly otherwise
    }

    private static String tail(String s, int lines) {
        if (s == null || s.isBlank()) return "(no output)";
        String[] all = s.split("\\R");
        int from = Math.max(0, all.length - lines);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < all.length; i++) sb.append(all[i]).append('\n');
        return sb.toString().stripTrailing();
    }
}
