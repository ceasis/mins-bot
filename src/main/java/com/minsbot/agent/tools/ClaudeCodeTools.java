package com.minsbot.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Primary code-generation tool. Shells out to the installed Claude Code CLI
 * ({@code claude -p ... --dangerously-skip-permissions}) inside a user-specified
 * working directory, letting Claude Code handle file writes, shell commands,
 * and git operations directly.
 */
@Component
public class ClaudeCodeTools {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeTools.class);
    private static final String NPM_CLAUDE_CMD =
            System.getProperty("user.home") + "\\AppData\\Roaming\\npm\\claude.cmd";
    private static final int TIMEOUT_MINUTES = 15;
    // NOTE: no silence-based kill. `claude -p` buffers ALL stdout until it
    // finishes (often 5+ min with zero bytes during the run). We rely on
    // TIMEOUT_MINUTES as the only hard cap, and surface progress by polling
    // the working directory for newly-written files instead.

    private final ToolExecutionNotifier notifier;
    private final ProjectBootstrapService bootstrap;
    private final ProjectHistoryService history;
    private final ObjectMapper mapper = new ObjectMapper();

    public ClaudeCodeTools(ToolExecutionNotifier notifier,
                           ProjectBootstrapService bootstrap,
                           ProjectHistoryService history) {
        this.notifier = notifier;
        this.bootstrap = bootstrap;
        this.history = history;
    }

    @Tool(description = "MANDATORY tool for creating/scaffolding/generating ANY project or multi-file codebase. "
            + "YOU MUST CALL THIS TOOL — DO NOT write code in the chat response. "
            + "DO NOT output file contents, pom.xml, application.properties, Java/JS/Python source, or any other code in your reply. "
            + "Any request like 'create/generate/scaffold/build me a <java|python|node|spring-boot|react> project/app/codebase/code for X' "
            + "is an instruction to call this tool once with a concise task description. "
            + "This tool delegates to the Claude Code CLI, which writes the files to disk and returns the list. "
            + "Failing to call this tool when the user asks for a project means NO files are created — never acceptable.")
    public String createCodeWithClaude(
            @ToolParam(description = "Plain-English coding task, e.g. 'Scaffold a Spring Boot web app "
                    + "named cool-site with Thymeleaf, a landing page, and git-init the folder'") String task,
            @ToolParam(description = "Absolute working directory, e.g. 'C:\\Users\\cholo\\code-gen\\cool-site'. "
                    + "Created if missing. All file writes happen inside this folder.") String workingDir) {
        return run(task, workingDir, true, false);
    }

    /** Direct invocation path used by CodeController (not a @Tool — avoids LLM routing). */
    public String run(String task, String workingDir, boolean createGithub, boolean isPrivate) {
        return runWithSink(task, workingDir, createGithub, isPrivate, CodeGenSink.NOOP);
    }

    /** Variant that also emits progress into {@link CodeGenSink} for SSE streaming / job records. */
    public String runWithSink(String task, String workingDir, boolean createGithub, boolean isPrivate,
                              CodeGenSink sink) {
        notifier.notify("Claude Code: " + abbreviate(task, 60) + "...");
        try {
            if (task == null || task.isBlank()) return "Error: task is required.";
            if (workingDir == null || workingDir.isBlank()) return "Error: workingDir is required.";

            Path dir = Path.of(workingDir);
            Files.createDirectories(dir);

            if (!new File(NPM_CLAUDE_CMD).exists()) {
                return "Claude Code CLI not found at " + NPM_CLAUDE_CMD
                        + ". Install with: npm install -g @anthropic-ai/claude-code";
            }

            List<String> cmd = new ArrayList<>();
            cmd.add(NPM_CLAUDE_CMD);
            cmd.add("-p");
            cmd.add(task);
            cmd.add("--dangerously-skip-permissions");
            // NOTE: `--output-format stream-json --verbose` would give us granular
            // tool-use events to parse, but in this CLI version it buffers and
            // emits nothing until exit, so progress UI stays blank for minutes.
            // Stick to plain text output; describeEvent() still soft-parses JSON
            // lines opportunistically if a future version streams them.

            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .directory(dir.toFile())
                    .redirectErrorStream(true)
                    // Redirect stdin from NUL so claude.cmd can't block waiting for input.
                    // Without this, the wrapped node process inherits an open stdin pipe
                    // and can hang indefinitely before emitting any stdout.
                    .redirectInput(new File("NUL"));
            log.info("[ClaudeCode] Running in {} — task: {}", dir, abbreviate(task, 100));
            sink.log("Launching claude -p in " + dir);
            Process p = pb.start();
            sink.setProcess(p);
            try { p.getOutputStream().close(); } catch (Exception ignored) {}

            StringBuilder buf = new StringBuilder();
            // Declared early so the heartbeat thread can cite the current count.
            // Seed with files that ALREADY exist in the project so the watcher only
            // reports files Claude actually creates/touches, not the pre-existing tree.
            java.util.Set<String> seenFiles = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
            java.util.Set<String> preExistingSkipDirs = java.util.Set.of(
                    ".git", "target", "build", "dist", "out", "node_modules",
                    ".gradle", ".idea", ".vscode", "__pycache__", ".pytest_cache",
                    ".next", ".nuxt", ".venv", "venv", "bin", "obj");
            try (java.util.stream.Stream<Path> s = java.nio.file.Files.walk(dir)) {
                s.filter(java.nio.file.Files::isRegularFile)
                 .filter(pp -> {
                     for (Path part : dir.relativize(pp)) {
                         if (preExistingSkipDirs.contains(part.toString())) return false;
                     }
                     return true;
                 })
                 .forEach(pp -> seenFiles.add(dir.relativize(pp).toString().replace('\\', '/')));
            } catch (Exception ignored) { /* empty dir is fine */ }
            log.info("[ClaudeCode] Watcher baseline: {} pre-existing file(s) in {}", seenFiles.size(), dir);
            final long[] lastOutputMs = { System.currentTimeMillis() };
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        lastOutputMs[0] = System.currentTimeMillis();
                        synchronized (buf) { buf.append(line).append('\n'); }
                        String trimmed = line.strip();
                        if (trimmed.isEmpty()) continue;
                        log.info("[ClaudeCode] stdout: {}", abbreviate(trimmed, 200));
                        sink.log(trimmed);
                        String msg = describeEvent(trimmed);
                        if (msg != null) notifier.notify(msg);
                    }
                } catch (Exception e) {
                    log.debug("[ClaudeCode] stdout reader error: {}", e.getMessage());
                }
            }, "claude-stdout");
            reader.setDaemon(true);
            reader.start();

            // Heartbeat: periodic progress. NO silence-based kill — claude -p
            // legitimately buffers all stdout until done. The notifier.notify
            // call is what makes each heartbeat visible in the chat UI (drained
            // by /api/chat/status every 500ms).
            Thread heartbeat = new Thread(() -> {
                long start = System.currentTimeMillis();
                try {
                    while (p.isAlive()) {
                        Thread.sleep(15_000);
                        if (!p.isAlive()) break;
                        long secs = (System.currentTimeMillis() - start) / 1000;
                        int bytes;
                        synchronized (buf) { bytes = buf.length(); }
                        int fileCount = seenFiles.size();
                        log.info("[ClaudeCode] heartbeat: alive={} secs={} files={} stdoutBytesRead={}",
                                p.isAlive(), secs, fileCount, bytes);
                        String m = fileCount > 0
                                ? "Claude is working (" + secs + "s, " + fileCount + " files touched)..."
                                : (secs < 60
                                        ? "Claude is thinking... (" + secs + "s)"
                                        : "Claude still working (" + secs + "s, reading & planning)...");
                        notifier.notify(m);
                        sink.log(m);
                    }
                } catch (InterruptedException ignored) {}
            }, "claude-heartbeat");

            // File-poll watcher: since `claude -p` doesn't stream stdout, we
            // can't know what it's doing from pipes. Instead, poll the working
            // directory every 3s and emit a notifier message for each new file.
            java.util.Set<String> watcherSkipDirs = java.util.Set.of(
                    ".git", "target", "build", "dist", "out", "node_modules",
                    ".gradle", ".idea", ".vscode", "__pycache__", ".pytest_cache",
                    ".next", ".nuxt", ".venv", "venv", "bin", "obj");
            Thread fileWatcher = new Thread(() -> {
                try {
                    while (p.isAlive()) {
                        Thread.sleep(3_000);
                        if (!java.nio.file.Files.isDirectory(dir)) continue;
                        try (java.util.stream.Stream<Path> s = java.nio.file.Files.walk(dir)) {
                            s.filter(java.nio.file.Files::isRegularFile)
                             .filter(pp -> {
                                 for (Path part : dir.relativize(pp)) {
                                     if (watcherSkipDirs.contains(part.toString())) return false;
                                 }
                                 return true;
                             })
                             .forEach(pp -> {
                                 String rel = dir.relativize(pp).toString().replace('\\', '/');
                                 if (seenFiles.add(rel)) {
                                     log.info("[ClaudeCode] detected new file: {}", rel);
                                     notifier.notify("Generating " + tail(rel) + "...");
                                     sink.file(rel);
                                 }
                             });
                        } catch (Exception ignored) {}
                    }
                } catch (InterruptedException ignored) {}
            }, "claude-file-watcher");
            fileWatcher.setDaemon(true);
            fileWatcher.start();
            heartbeat.setDaemon(true);
            heartbeat.start();

            boolean finished = p.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
            heartbeat.interrupt();
            fileWatcher.interrupt();
            if (!finished) {
                p.destroyForcibly();
                reader.join(2000);
                // Still bootstrap if claude wrote files before the timeout — better than losing work.
                String fileListTo = listFilesWritten(dir);
                String tail = "Error: claude timed out after " + TIMEOUT_MINUTES + " min.\n\nPartial output:\n"
                        + buf.toString() + (fileListTo.isEmpty() ? "" : "\n\n" + fileListTo);
                boolean hadFilesTo = !fileListTo.isEmpty() && !fileListTo.contains("(none)");
                if (createGithub && hadFilesTo) tail += bootstrap.bootstrap(dir, isPrivate);
                history.record(null, "primary", task, workingDir,
                        hadFilesTo ? "timed-out-with-files" : "timed-out", tail);
                return tail;
            }
            reader.join(2000);
            int exit = p.exitValue();
            String output;
            synchronized (buf) { output = buf.toString(); }
            String header = "[Claude Code] exit=" + exit + " dir=" + dir + "\n\n";
            String fileList = listFilesWritten(dir);
            boolean anyFiles = !fileList.isEmpty() && !fileList.contains("(none)");
            String body = header
                    + (output.isBlank() ? "(no stdout captured)" : output)
                    + (fileList.isEmpty() ? "" : "\n\n" + fileList);
            // Bootstrap if files were created, even if exit != 0 — claude may have been
            // killed or errored after writing files, and we still want them checked in.
            if (createGithub && anyFiles) {
                sink.status("pushing");
                sink.log("Initializing git and pushing to GitHub...");
                String bs = bootstrap.bootstrap(dir, isPrivate);
                body += bs;
                if (!bs.isBlank()) sink.log(bs.trim());
            }
            if (!anyFiles && output.isBlank()) {
                body += "\n\nNo files created and no output. Try running manually:\n"
                      + "  cd " + dir + "\n"
                      + "  claude -p \"hello\" --dangerously-skip-permissions";
            }
            history.record(null, "primary", task, workingDir,
                    anyFiles ? (exit == 0 ? "done" : "done-with-errors") : "failed", body);
            return body;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Parse one line of claude stream-json output into a human-friendly progress message.
     * Returns null to skip noisy events. On parse failure, returns the raw line.
     */
    private String describeEvent(String line) {
        if (!line.startsWith("{")) return "claude: " + abbreviate(line, 140);
        try {
            JsonNode ev = mapper.readTree(line);
            String type = ev.path("type").asText("");
            switch (type) {
                case "system": {
                    String sub = ev.path("subtype").asText("");
                    if ("init".equals(sub)) return "claude: initializing...";
                    return null;
                }
                case "assistant": {
                    JsonNode blocks = ev.path("message").path("content");
                    if (!blocks.isArray()) return null;
                    for (JsonNode b : blocks) {
                        String bt = b.path("type").asText("");
                        if ("tool_use".equals(bt)) {
                            String name = b.path("name").asText("");
                            JsonNode in = b.path("input");
                            switch (name) {
                                case "Write":     return "Generating " + tail(in.path("file_path").asText("")) + "...";
                                case "Edit":      return "Editing "    + tail(in.path("file_path").asText("")) + "...";
                                case "MultiEdit": return "Editing "    + tail(in.path("file_path").asText("")) + "...";
                                case "Read":      return "Reading "    + tail(in.path("file_path").asText("")) + "...";
                                case "Bash": {
                                    String cmd = in.path("command").asText("");
                                    String desc = in.path("description").asText("");
                                    return "Running: " + abbreviate(!desc.isBlank() ? desc : cmd, 100) + "...";
                                }
                                case "Glob":     return "Searching: "  + in.path("pattern").asText("") + "...";
                                case "Grep":     return "Searching: "  + in.path("pattern").asText("") + "...";
                                case "TodoWrite": return null; // too noisy
                                default:         return "Using tool: " + name + "...";
                            }
                        } else if ("text".equals(bt)) {
                            String t = b.path("text").asText("").strip();
                            if (t.length() > 8) return "claude: " + abbreviate(t, 140);
                        }
                    }
                    return null;
                }
                case "user":    return null; // tool_result echoes — skip
                case "result": {
                    String sub = ev.path("subtype").asText("");
                    if ("success".equals(sub)) return "claude: done.";
                    return "claude: finished (" + sub + ").";
                }
                default: return null;
            }
        } catch (Exception e) {
            return "claude: " + abbreviate(line, 140);
        }
    }

    /**
     * After claude exits, walk the working directory and log every file it wrote.
     * Returns a human-readable summary suitable for appending to the tool output.
     */
    private String listFilesWritten(Path dir) {
        try {
            if (dir == null || !java.nio.file.Files.isDirectory(dir)) return "";
            java.util.List<String> rels = new java.util.ArrayList<>();
            // Ignore build outputs and VCS/IDE metadata so the "files written" summary
            // reflects what Claude actually authored, not Maven/Gradle/npm leftovers.
            java.util.Set<String> skipDirs = java.util.Set.of(
                    ".git", "target", "build", "dist", "out", "node_modules",
                    ".gradle", ".idea", ".vscode", "__pycache__", ".pytest_cache",
                    ".next", ".nuxt", ".venv", "venv", "bin", "obj");
            try (java.util.stream.Stream<Path> s = java.nio.file.Files.walk(dir)) {
                s.filter(java.nio.file.Files::isRegularFile)
                 .filter(pp -> {
                     for (Path part : dir.relativize(pp)) {
                         if (skipDirs.contains(part.toString())) return false;
                     }
                     return true;
                 })
                 .limit(200)
                 .forEach(pp -> rels.add(dir.relativize(pp).toString().replace('\\', '/')));
            }
            if (rels.isEmpty()) {
                log.info("[ClaudeCode] No files were written in {}", dir);
                return "Files written: (none)";
            }
            java.util.Collections.sort(rels);
            log.info("[ClaudeCode] {} file(s) written in {}:", rels.size(), dir);
            for (String r : rels) log.info("[ClaudeCode]   {}", r);
            StringBuilder sb = new StringBuilder("Files written (" + rels.size() + "):\n");
            for (String r : rels) sb.append("  • ").append(r).append('\n');
            return sb.toString();
        } catch (Exception e) {
            log.warn("[ClaudeCode] listFilesWritten failed: {}", e.getMessage());
            return "";
        }
    }

    private static String tail(String path) {
        if (path == null || path.isEmpty()) return "(file)";
        int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return i >= 0 ? path.substring(i + 1) : path;
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
