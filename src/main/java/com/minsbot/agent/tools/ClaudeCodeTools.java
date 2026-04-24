package com.minsbot.agent.tools;

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
    private static final int TIMEOUT_MINUTES = 10;

    private final ToolExecutionNotifier notifier;

    public ClaudeCodeTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @Tool(description = "PRIMARY tool for creating code, scaffolding projects, or writing multi-file features. "
            + "Delegates to the Claude Code CLI which actually writes files to disk (not fake plan summaries). "
            + "Call this for requests like 'create me a java project for X', 'scaffold a python api', "
            + "'build me a react app', 'write a cli tool that does Y'. "
            + "The CLI handles file writes, git init, installs, etc. Returns its output log.")
    public String createCodeWithClaude(
            @ToolParam(description = "Plain-English coding task, e.g. 'Scaffold a Spring Boot web app "
                    + "named cool-site with Thymeleaf, a landing page, and git-init the folder'") String task,
            @ToolParam(description = "Absolute working directory, e.g. 'C:\\Users\\cholo\\code-gen\\cool-site'. "
                    + "Created if missing. All file writes happen inside this folder.") String workingDir) {
        return run(task, workingDir);
    }

    /** Direct invocation path used by CodeController (not a @Tool — avoids LLM routing). */
    public String run(String task, String workingDir) {
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

            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .directory(dir.toFile())
                    .redirectErrorStream(true);
            log.info("[ClaudeCode] Running in {} — task: {}", dir, abbreviate(task, 100));
            Process p = pb.start();

            StringBuilder buf = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        synchronized (buf) { buf.append(line).append('\n'); }
                        String trimmed = line.strip();
                        if (!trimmed.isEmpty()) notifier.notify("claude: " + abbreviate(trimmed, 140));
                    }
                } catch (Exception e) {
                    log.debug("[ClaudeCode] stdout reader error: {}", e.getMessage());
                }
            }, "claude-stdout");
            reader.setDaemon(true);
            reader.start();

            Thread heartbeat = new Thread(() -> {
                long start = System.currentTimeMillis();
                try {
                    while (p.isAlive()) {
                        Thread.sleep(20_000);
                        if (!p.isAlive()) break;
                        long secs = (System.currentTimeMillis() - start) / 1000;
                        notifier.notify("claude still working (" + secs + "s)...");
                    }
                } catch (InterruptedException ignored) {}
            }, "claude-heartbeat");
            heartbeat.setDaemon(true);
            heartbeat.start();

            boolean finished = p.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
            heartbeat.interrupt();
            if (!finished) {
                p.destroyForcibly();
                reader.join(2000);
                return "Error: claude timed out after " + TIMEOUT_MINUTES + " min.\n\nPartial output:\n"
                        + buf.toString();
            }
            reader.join(2000);
            int exit = p.exitValue();
            String output;
            synchronized (buf) { output = buf.toString(); }
            String header = "[Claude Code] exit=" + exit + " dir=" + dir + "\n\n";
            return header + (output.isBlank() ? "(no output)" : output);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
