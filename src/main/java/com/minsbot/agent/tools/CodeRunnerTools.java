package com.minsbot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Run Python, Node.js, PowerShell, or shell code snippets in a temp file and capture output.
 */
@Component
public class CodeRunnerTools {

    private static final Logger log = LoggerFactory.getLogger(CodeRunnerTools.class);
    private static final int MAX_OUTPUT = 4000;
    private static final int DEFAULT_TIMEOUT = 30;

    @Tool(description = "Execute a Python code snippet and return the output (stdout, stderr, exit code). Python must be installed.")
    public String runPython(
            @ToolParam(description = "Python code to execute") String code,
            @ToolParam(description = "Timeout in seconds (default 30, max 120)") Integer timeoutSeconds) {
        return runViaInterpreter(code, ".py", detectPython(), timeoutSeconds);
    }

    @Tool(description = "Execute a Node.js JavaScript snippet and return the output. Node.js must be installed.")
    public String runNodeJs(
            @ToolParam(description = "JavaScript code to execute") String code,
            @ToolParam(description = "Timeout in seconds (default 30, max 120)") Integer timeoutSeconds) {
        return runViaInterpreter(code, ".js", "node", timeoutSeconds);
    }

    @com.minsbot.approval.RequiresApproval(
            value = com.minsbot.approval.RiskLevel.DESTRUCTIVE,
            summary = "Run PowerShell script: {script}")
    @Tool(description = "Execute a PowerShell script and return the output.")
    public String runPowerShell(
            @ToolParam(description = "PowerShell script to execute") String script,
            @ToolParam(description = "Timeout in seconds (default 30, max 120)") Integer timeoutSeconds) {
        return runWithPrefix(script, ".ps1", timeoutSeconds, "powershell", "-ExecutionPolicy", "Bypass", "-File");
    }

    @com.minsbot.approval.RequiresApproval(
            value = com.minsbot.approval.RiskLevel.DESTRUCTIVE,
            summary = "Run shell command: {script}")
    @Tool(description = "Execute a shell command or script (bash on Linux/macOS, cmd on Windows) and return the output.")
    public String runShell(
            @ToolParam(description = "Shell commands or script to execute") String script,
            @ToolParam(description = "Timeout in seconds (default 30, max 120)") Integer timeoutSeconds) {
        boolean isWin = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (isWin) {
            return runWithPrefix(script, ".bat", timeoutSeconds, "cmd", "/c");
        }
        return runViaInterpreter(script, ".sh", "bash", timeoutSeconds);
    }

    // ─── Internal ───

    private String runViaInterpreter(String code, String ext, String interpreter, Integer timeout) {
        List<String> cmd = new ArrayList<>();
        cmd.add(interpreter);
        return execute(code, ext, timeout, cmd);
    }

    private String runWithPrefix(String code, String ext, Integer timeout, String... prefix) {
        return execute(code, ext, timeout, new ArrayList<>(List.of(prefix)));
    }

    private String execute(String code, String ext, Integer timeoutSec, List<String> prefixCmd) {
        int t = clamp(timeoutSec != null ? timeoutSec : DEFAULT_TIMEOUT, 1, 120);
        Path tmp = null;
        try {
            tmp = Files.createTempFile("minsbot_run_", ext);
            Files.writeString(tmp, code);
            tmp.toFile().deleteOnExit();

            List<String> cmd = new ArrayList<>(prefixCmd);
            cmd.add(tmp.toAbsolutePath().toString());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            Process proc = pb.start();

            byte[] outBytes = proc.getInputStream().readAllBytes();
            byte[] errBytes = proc.getErrorStream().readAllBytes();
            boolean done = proc.waitFor(t, TimeUnit.SECONDS);
            if (!done) proc.destroyForcibly();

            int exit = done ? proc.exitValue() : -1;
            String stdout = truncate(new String(outBytes).trim(), MAX_OUTPUT / 2);
            String stderr = truncate(new String(errBytes).trim(), MAX_OUTPUT / 2);

            StringBuilder sb = new StringBuilder();
            sb.append("Exit: ").append(exit).append(done ? "" : " (timed out after " + t + "s)").append("\n");
            if (!stdout.isEmpty()) sb.append("--- stdout ---\n").append(stdout).append("\n");
            if (!stderr.isEmpty()) sb.append("--- stderr ---\n").append(stderr).append("\n");
            if (sb.toString().strip().equals("Exit: 0")) sb.append("(no output)");
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("[CodeRunner] Failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        } finally {
            if (tmp != null) try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }
    }

    private String detectPython() {
        for (String exe : new String[]{"python3", "python"}) {
            try {
                Process p = new ProcessBuilder(exe, "--version").start();
                if (p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0) return exe;
            } catch (Exception ignored) {}
        }
        return "python";
    }

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "\n…(truncated)" : (s != null ? s : "");
    }

    private int clamp(int v, int min, int max) {
        return Math.min(max, Math.max(min, v));
    }
}
