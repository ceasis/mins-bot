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

    @com.minsbot.approval.RequiresApproval(
            value = com.minsbot.approval.RiskLevel.DESTRUCTIVE,
            summary = "Build & run project at: {projectDir}")
    @Tool(description = "Build AND run a software project at a given directory. Auto-detects: "
            + "Maven (pom.xml → 'mvn spring-boot:run' or 'mvn package + java -jar'), "
            + "Gradle (build.gradle → 'gradle bootRun' or 'gradle run'), "
            + "Node/npm (package.json → 'npm start' if defined, else 'node index.js'). "
            + "Use this when the user says 'run it', 'start the app', 'launch the project', "
            + "'run the spring boot app at X', 'start the npm server'. "
            + "Runs the build IN-PROCESS and waits for it to finish. Long-running servers spawn "
            + "as a child process so the chat stays responsive — output is captured during "
            + "startup (default 60s warmup) then the process keeps running. Returns build "
            + "result + first 60s of server logs. To stop the server later, use stopProject(token).")
    public String runProject(
            @ToolParam(description = "Absolute path to the project root (the folder containing "
                    + "pom.xml / build.gradle / package.json)") String projectDir,
            @ToolParam(description = "Optional override command (e.g. 'mvn -DskipTests spring-boot:run'). "
                    + "Empty string for auto-detect.") String overrideCommand,
            @ToolParam(description = "Warmup seconds — how long to capture output before returning "
                    + "while the server keeps running. Default 60, max 180.") Integer warmupSeconds) {
        if (projectDir == null || projectDir.isBlank()) return "Error: projectDir is required.";
        Path dir = java.nio.file.Paths.get(projectDir.trim());
        if (!Files.isDirectory(dir)) return "Error: not a directory: " + dir;

        boolean isWin = System.getProperty("os.name", "").toLowerCase().contains("win");
        List<String> cmd = new ArrayList<>();
        String detected;
        if (overrideCommand != null && !overrideCommand.isBlank()) {
            // Honor explicit override. Use shell to allow flag-passing as one string.
            if (isWin) { cmd.add("cmd"); cmd.add("/c"); }
            else       { cmd.add("bash"); cmd.add("-lc"); }
            cmd.add(overrideCommand.trim());
            detected = "override(" + overrideCommand.trim() + ")";
        } else if (Files.isRegularFile(dir.resolve("pom.xml"))) {
            // Maven. Spring Boot if it's a spring-boot-starter-parent / has spring-boot plugin.
            boolean isSpringBoot = readMaybe(dir.resolve("pom.xml")).contains("spring-boot");
            String mvn = isWin ? "mvn.cmd" : "mvn";
            if (isSpringBoot) {
                cmd.addAll(List.of(mvn, "-DskipTests", "spring-boot:run"));
                detected = "maven-spring-boot";
            } else {
                cmd.addAll(List.of(mvn, "-DskipTests", "exec:java"));
                detected = "maven-exec";
            }
        } else if (Files.isRegularFile(dir.resolve("build.gradle"))
                || Files.isRegularFile(dir.resolve("build.gradle.kts"))) {
            String gradle = Files.isRegularFile(dir.resolve(isWin ? "gradlew.bat" : "gradlew"))
                    ? (isWin ? ".\\gradlew.bat" : "./gradlew") : "gradle";
            String build = readMaybe(dir.resolve("build.gradle"))
                    + readMaybe(dir.resolve("build.gradle.kts"));
            String task = build.contains("org.springframework.boot") ? "bootRun" : "run";
            if (isWin) { cmd.add("cmd"); cmd.add("/c"); cmd.add(gradle + " " + task); }
            else       { cmd.add("bash"); cmd.add("-lc"); cmd.add(gradle + " " + task); }
            detected = "gradle-" + task;
        } else if (Files.isRegularFile(dir.resolve("package.json"))) {
            String pkg = readMaybe(dir.resolve("package.json"));
            String npmCmd = pkg.contains("\"start\"") ? "npm start" : "node index.js";
            if (isWin) { cmd.add("cmd"); cmd.add("/c"); cmd.add(npmCmd); }
            else       { cmd.add("bash"); cmd.add("-lc"); cmd.add(npmCmd); }
            detected = "node(" + npmCmd + ")";
        } else {
            return "Error: no recognizable project marker found in " + dir
                    + " (looked for pom.xml, build.gradle, package.json). "
                    + "Pass overrideCommand explicitly if you know the run command.";
        }

        int warmup = clamp(warmupSeconds != null ? warmupSeconds : 60, 5, 180);
        log.info("[runProject] dir={} detected={} cmd={}", dir, detected, cmd);

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
            Process proc = pb.start();
            String token = "rp-" + System.currentTimeMillis();
            RUNNING.put(token, proc);

            // Capture output during warmup window.
            StringBuilder out = new StringBuilder();
            long deadline = System.currentTimeMillis() + warmup * 1000L;
            byte[] buf = new byte[4096];
            java.io.InputStream in = proc.getInputStream();
            while (System.currentTimeMillis() < deadline) {
                if (in.available() > 0) {
                    int n = in.read(buf);
                    if (n > 0) {
                        out.append(new String(buf, 0, n));
                        if (out.length() > MAX_OUTPUT) {
                            out.setLength(MAX_OUTPUT);
                            out.append("\n…(output truncated)");
                            break;
                        }
                    }
                } else {
                    if (!proc.isAlive()) break;
                    Thread.sleep(150);
                }
            }
            boolean stillRunning = proc.isAlive();
            int exit = stillRunning ? -1 : proc.exitValue();

            StringBuilder result = new StringBuilder();
            result.append("Project: ").append(dir.toAbsolutePath()).append("\n");
            result.append("Detected: ").append(detected).append("\n");
            result.append("Command: ").append(String.join(" ", cmd)).append("\n");
            if (stillRunning) {
                result.append("Status: RUNNING (warmup ").append(warmup).append("s elapsed) — token: ")
                      .append(token).append("\n");
                result.append("Stop with: stopProject('").append(token).append("')\n");
            } else {
                RUNNING.remove(token);
                result.append("Status: EXITED with code ").append(exit).append("\n");
            }
            if (out.length() > 0) {
                result.append("\n--- output ---\n").append(out);
            }
            return result.toString();
        } catch (Exception e) {
            log.warn("[runProject] failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    @com.minsbot.approval.RequiresApproval(
            value = com.minsbot.approval.RiskLevel.SIDE_EFFECT,
            summary = "Stop running project: {token}")
    @Tool(description = "Stop a project that runProject started. Pass the token returned by runProject. "
            + "Use when the user says 'stop the app', 'kill the server', 'stop X'.")
    public String stopProject(
            @ToolParam(description = "Token from runProject (e.g. 'rp-1730000000000')") String token) {
        if (token == null || token.isBlank()) return "Error: token is required.";
        Process p = RUNNING.remove(token.trim());
        if (p == null) return "No running project with token: " + token + ". List active with listRunningProjects.";
        try {
            p.destroy();
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly();
            return "Stopped: " + token;
        } catch (Exception e) {
            return "Stop failed: " + e.getMessage();
        }
    }

    @Tool(description = "List running projects started by runProject. Returns each token + alive state.")
    public String listRunningProjects() {
        if (RUNNING.isEmpty()) return "No projects currently running.";
        StringBuilder sb = new StringBuilder("Running projects:\n");
        RUNNING.forEach((k, p) -> sb.append("  ").append(k).append(" — ")
                .append(p.isAlive() ? "alive (pid " + p.pid() + ")" : "exited").append("\n"));
        return sb.toString().trim();
    }

    /** Tracks long-lived child processes spawned by {@link #runProject}. */
    private static final java.util.concurrent.ConcurrentHashMap<String, Process> RUNNING =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static String readMaybe(Path p) {
        try { return Files.isRegularFile(p) ? Files.readString(p) : ""; }
        catch (Exception e) { return ""; }
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
