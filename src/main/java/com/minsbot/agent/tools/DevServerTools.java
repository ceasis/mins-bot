package com.minsbot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tools to start/stop long-running dev servers WITHOUT blocking the main agent
 * loop. Replaces the "just call runPowerShell with mvn spring-boot:run" anti-pattern
 * that always hangs the bot and gets killed by the approval gate.
 *
 * <p>Servers are launched detached via {@code Start-Process} with stdout/stderr
 * redirected to log files the user can tail. The PID is returned so {@link #stopDevServer}
 * can kill it later.</p>
 */
@Component
public class DevServerTools {

    private static final Logger log = LoggerFactory.getLogger(DevServerTools.class);

    /** Known dev-server shorthands → full command string. */
    private static final Map<String, String> PRESETS = Map.of(
            "spring-boot",   "mvn -q -DskipTests spring-boot:run",
            "gradle-boot",   "./gradlew bootRun",
            "npm-dev",       "npm run dev",
            "npm-start",     "npm start",
            "pnpm-dev",      "pnpm dev",
            "vite",          "npx vite",
            "flask",         "flask run",
            "python-app",    "python app.py",
            "node-server",   "node server.js",
            "docker-up",     "docker compose up"
    );

    /** pid → {dir, command, logPath, startedAt}. Lets stopDevServer list/kill. */
    private final Map<Long, Map<String, Object>> running = new ConcurrentHashMap<>();

    private final ToolExecutionNotifier notifier;

    public DevServerTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @Tool(description = "Start a long-running development server (Spring Boot, Vite, Flask, etc.) "
            + "in the BACKGROUND without blocking. "
            + "USE THIS TOOL — do NOT call mvn/npm/flask dev servers through runPowerShell/runCmd, "
            + "they hang the agent and get killed by the approval gate. "
            + "Launches via Start-Process (detached) with stdout/stderr logged to run.log/run.err "
            + "in the project folder. Returns the PID so it can be stopped with stopDevServer. "
            + "Pass `port` to override the default — for Spring Boot presets this writes server.port=N "
            + "into src/main/resources/application.properties so args don't need quoting.")
    public String startDevServer(
            @ToolParam(description = "Project folder (absolute path)") String workingDir,
            @ToolParam(description = "Either a preset shortcut (spring-boot, gradle-boot, npm-dev, npm-start, "
                    + "pnpm-dev, vite, flask, python-app, node-server, docker-up) OR the full command. "
                    + "For port overrides use the `port` parameter — don't embed --server.port in this string.") String commandOrPreset,
            @ToolParam(description = "Port to bind on (0 = use project default). For spring-boot/gradle-boot "
                    + "this writes server.port=N into application.properties before launch. "
                    + "For other stacks the port must be in the command itself or the project config.") int port) {
        try {
            if (workingDir == null || workingDir.isBlank()) return "Error: workingDir is required.";
            if (commandOrPreset == null || commandOrPreset.isBlank()) return "Error: commandOrPreset is required.";

            Path dir = Path.of(workingDir);
            if (!Files.isDirectory(dir)) return "Error: folder does not exist: " + workingDir;

            String presetKey = commandOrPreset.toLowerCase(Locale.ROOT);
            String cmd = PRESETS.getOrDefault(presetKey, commandOrPreset).trim();

            // Port injection for Spring Boot — writing to application.properties
            // sidesteps all the nested-quote pain of passing --server.port through
            // Start-Process -ArgumentList -Command.
            String portNote = "";
            if (port > 0 && (presetKey.equals("spring-boot") || presetKey.equals("gradle-boot"))) {
                portNote = injectSpringPort(dir, port);
            }

            Path logOut = dir.resolve("run.log");
            Path logErr = dir.resolve("run.err");
            Path runScript = dir.resolve(".run-dev-server.ps1");
            try { Files.deleteIfExists(logOut); } catch (Exception ignored) {}
            try { Files.deleteIfExists(logErr); } catch (Exception ignored) {}

            // Drop the actual command into a .ps1 file so we don't fight nested
            // PowerShell quoting across three layers. The detached process just
            // runs this file; Start-Process sets the working directory + redirect.
            Files.writeString(runScript,
                    "$ErrorActionPreference = 'Stop'\n" + cmd + "\n",
                    StandardCharsets.UTF_8);

            String launcher =
                    "$p = Start-Process -PassThru -WindowStyle Hidden -FilePath 'powershell.exe' " +
                    "-ArgumentList '-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File','" +
                    runScript.toString().replace("'", "''") + "' " +
                    "-WorkingDirectory '" + dir.toString().replace("'", "''") + "' " +
                    "-RedirectStandardOutput '" + logOut.toString().replace("'", "''") + "' " +
                    "-RedirectStandardError '"  + logErr.toString().replace("'", "''") + "'; " +
                    "Write-Output $p.Id";

            List<String> exec = new ArrayList<>();
            exec.add("powershell.exe");
            exec.add("-NoProfile");
            exec.add("-NonInteractive");
            exec.add("-Command");
            exec.add(launcher);

            ProcessBuilder pb = new ProcessBuilder(exec)
                    .directory(dir.toFile())
                    .redirectErrorStream(true);
            log.info("[DevServer] Launching in {}: {}", dir, cmd);
            notifier.notify("Launching dev server: " + cmd);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor();

            long pid = -1;
            for (String line : out.split("\\R")) {
                String t = line.trim();
                if (t.matches("\\d+")) { pid = Long.parseLong(t); break; }
            }
            if (pid <= 0) {
                return "Launch completed but could not parse PID. Launcher output:\n" + out;
            }

            Map<String, Object> info = new LinkedHashMap<>();
            info.put("dir", dir.toString());
            info.put("command", cmd);
            info.put("logOut", logOut.toString());
            info.put("logErr", logErr.toString());
            info.put("startedAt", java.time.Instant.now().toString());
            running.put(pid, info);

            // Verify the child actually stays alive AND (if a port is known) binds to it.
            // A bare alive-check is misleading for Spring Boot — the parent mvn process stays
            // alive for 30-60s during compile while the JVM hasn't bound anything yet.
            // Wait up to 45s; bail early on port-bound OR exit OR "Started" in the log.
            boolean alive = pollServerReady(pid, logOut, logErr, port, 45);
            String logTail = tailFile(logOut, 40);
            String errTail = tailFile(logErr, 15);

            StringBuilder r = new StringBuilder();
            r.append(alive ? "✓ Server running" : "✗ Server exited early")
             .append(" — PID ").append(pid).append(", command: ").append(cmd).append('\n');
            if (!portNote.isEmpty()) r.append("  port:   ").append(portNote).append('\n');
            r.append("  dir:    ").append(dir).append('\n');
            r.append("  stdout: ").append(logOut).append('\n');
            r.append("  stderr: ").append(logErr).append('\n');
            if (!alive) {
                r.append("\nThe process exited before boot completed. ")
                 .append("Most common causes: 'mvn' / 'npm' not on PATH, port already in use, build failure.\n");
                notifier.notify("✗ Dev server exited early — check run.err for the real error.");
            }
            if (!logTail.isBlank()) {
                r.append("\n── stdout (last 30 lines) ──\n").append(logTail);
            }
            if (!errTail.isBlank()) {
                r.append("\n── stderr (last 10 lines) ──\n").append(errTail);
            }
            if (alive) {
                // Parse the boot log for registered routes + startup confirmation.
                // Saves the user from discovering "no / route → 404" the hard way.
                String routeReport = summarizeBootLog(logOut, port);
                if (!routeReport.isEmpty()) r.append("\n").append(routeReport).append('\n');
                r.append("\nTail live: Get-Content -Wait ").append(logOut).append('\n');
                r.append("Stop:      stopDevServer(").append(pid).append(")");
            } else {
                running.remove(pid);
            }
            return r.toString();
        } catch (Exception e) {
            log.warn("[DevServer] start failed", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "List all dev servers started by startDevServer in this session (PID, dir, command).")
    public String listDevServers() {
        if (running.isEmpty()) return "No dev servers tracked in this session.";
        StringBuilder sb = new StringBuilder("Tracked dev servers:\n");
        running.forEach((pid, info) -> {
            sb.append("  PID ").append(pid).append(" — ").append(info.get("command"))
              .append(" (started ").append(info.get("startedAt")).append(")")
              .append("\n    dir: ").append(info.get("dir"))
              .append("\n    log: ").append(info.get("logOut"))
              .append("\n");
        });
        return sb.toString();
    }

    @Tool(description = "Read the most recent lines of a dev server's log. "
            + "Use this to diagnose why a server failed to start ('why is my spring-boot not running', "
            + "'show the error log', 'what went wrong'). Set stream=stderr to see errors, "
            + "stream=stdout for normal output, stream=both to see both concatenated.")
    public String readDevServerLog(
            @ToolParam(description = "PID returned by startDevServer") long pid,
            @ToolParam(description = "Max lines from the tail (e.g. 100)") int maxLines,
            @ToolParam(description = "Which stream: 'stdout' | 'stderr' | 'both'. Default stdout.") String stream) {
        Map<String, Object> info = running.get(pid);
        if (info == null) return "Unknown PID: " + pid + ". Call listDevServers to see tracked servers, "
                + "or use readProjectDevLog(workingDir, ...) to read run.log / run.err by folder.";
        int lines = maxLines <= 0 ? 100 : Math.min(maxLines, 500);
        String which = (stream == null || stream.isBlank()) ? "stdout" : stream.trim().toLowerCase(Locale.ROOT);
        Path logOut = Path.of(info.get("logOut").toString());
        Path logErr = Path.of(info.get("logErr").toString());
        try {
            switch (which) {
                case "stderr": return tailBlock(logErr, lines);
                case "both":   return tailBlock(logOut, lines) + "\n" + tailBlock(logErr, lines);
                default:       return tailBlock(logOut, lines);
            }
        } catch (Exception e) {
            return "Error reading log: " + e.getMessage();
        }
    }

    @Tool(description = "Read a dev server's log directly from a project folder without needing a PID. "
            + "Reads run.log (stdout) and run.err (stderr) that startDevServer writes into the project dir. "
            + "Use when the user asks 'show the error log for <project>' or 'why did <project> fail to start' "
            + "and the PID is unknown or the server already exited. Set stream to stdout, stderr, or both.")
    public String readProjectDevLog(
            @ToolParam(description = "Project folder (absolute path) — the folder passed to startDevServer") String workingDir,
            @ToolParam(description = "Which stream: 'stdout' | 'stderr' | 'both'. Default both (usually what you want for debugging).") String stream,
            @ToolParam(description = "Max lines from the tail (e.g. 150)") int maxLines) {
        try {
            if (workingDir == null || workingDir.isBlank()) return "Error: workingDir is required.";
            Path dir = Path.of(workingDir);
            if (!Files.isDirectory(dir)) return "Folder does not exist: " + workingDir;
            Path logOut = dir.resolve("run.log");
            Path logErr = dir.resolve("run.err");
            int lines = maxLines <= 0 ? 150 : Math.min(maxLines, 500);
            String which = (stream == null || stream.isBlank()) ? "both" : stream.trim().toLowerCase(Locale.ROOT);
            StringBuilder sb = new StringBuilder();
            boolean hasOut = Files.exists(logOut) && Files.size(logOut) > 0;
            boolean hasErr = Files.exists(logErr) && Files.size(logErr) > 0;
            if (!hasOut && !hasErr) {
                return "No run.log or run.err in " + dir + ". Either the server wasn't started via "
                        + "startDevServer, or nothing has been written yet.";
            }
            if (which.equals("stdout") || which.equals("both")) {
                if (hasOut) sb.append(tailBlock(logOut, lines)).append('\n');
                else sb.append("(run.log is missing or empty)\n");
            }
            if (which.equals("stderr") || which.equals("both")) {
                if (hasErr) sb.append(tailBlock(logErr, lines));
                else sb.append("(run.err is missing or empty — no errors captured)");
            }
            return sb.toString();
        } catch (Exception e) {
            return "Error reading logs: " + e.getMessage();
        }
    }

    private String tailBlock(Path file, int lines) throws java.io.IOException {
        if (!Files.exists(file)) return "── " + file.getFileName() + " (not found) ──\n";
        List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
        int from = Math.max(0, all.size() - lines);
        StringBuilder sb = new StringBuilder();
        sb.append("── ").append(file).append(" (last ").append(all.size() - from)
          .append(" of ").append(all.size()).append(" line(s)) ──\n");
        if (all.isEmpty()) sb.append("(empty)\n");
        else for (int i = from; i < all.size(); i++) sb.append(all.get(i)).append('\n');
        return sb.toString();
    }

    @Tool(description = "Find and show which process is currently listening on a TCP port. "
            + "Useful when a dev server fails to start because the port is already in use "
            + "('port 8080 already in use'). Returns PID, process name, and command line.")
    public String whoIsUsingPort(
            @ToolParam(description = "TCP port number, e.g. 8080") int port) {
        try {
            if (port <= 0 || port > 65535) return "Invalid port: " + port;
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command",
                    "$conns = Get-NetTCPConnection -LocalPort " + port + " -State Listen -ErrorAction SilentlyContinue; " +
                    "if (-not $conns) { 'No process is listening on port " + port + ".' } " +
                    "else { " +
                    "  $conns | ForEach-Object { " +
                    "    $proc = Get-Process -Id $_.OwningProcess -ErrorAction SilentlyContinue; " +
                    "    if ($proc) { 'PID {0} - {1} ({2})' -f $_.OwningProcess, $proc.ProcessName, $proc.Path } " +
                    "    else { 'PID {0} - (process info unavailable)' -f $_.OwningProcess } " +
                    "  } " +
                    "}")
                    .redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor();
            return out.isEmpty() ? "No process found on port " + port + "." : out;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "Kill whatever process is currently bound to the given TCP port. "
            + "Use BEFORE retrying startDevServer when it fails with 'port already in use', "
            + "or when the user says 'free up port 8080', 'kill whatever is on 3000', etc. "
            + "This is destructive — only call when the user explicitly wants to free the port. "
            + "Returns the PID(s) killed (or reports nothing was listening).")
    public String killProcessOnPort(
            @ToolParam(description = "TCP port number, e.g. 8080") int port) {
        try {
            if (port <= 0 || port > 65535) return "Invalid port: " + port;
            log.info("[DevServer] killProcessOnPort({}) requested", port);
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command",
                    // Gather PIDs on the port (LISTEN and ESTABLISHED owned by this port).
                    "$pids = @(); " +
                    "$listen = Get-NetTCPConnection -LocalPort " + port + " -State Listen -ErrorAction SilentlyContinue; " +
                    "if ($listen) { $pids += $listen.OwningProcess } " +
                    "$pids = $pids | Select-Object -Unique; " +
                    "if (-not $pids -or $pids.Count -eq 0) { " +
                    "  Write-Output 'nothing-listening'; " +
                    "} else { " +
                    "  foreach ($pid2 in $pids) { " +
                    "    $proc = Get-Process -Id $pid2 -ErrorAction SilentlyContinue; " +
                    "    $name = if ($proc) { $proc.ProcessName } else { '?' }; " +
                    "    try { " +
                    "      Stop-Process -Id $pid2 -Force -ErrorAction Stop; " +
                    "      Write-Output ('killed {0} ({1})' -f $pid2, $name); " +
                    "    } catch { " +
                    "      Write-Output ('failed {0} ({1}): {2}' -f $pid2, $name, $_.Exception.Message); " +
                    "    } " +
                    "  } " +
                    "}")
                    .redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor();
            if (out.isEmpty()) return "No output — port " + port + " status unknown.";
            if (out.contains("nothing-listening")) return "Port " + port + " is free — nothing was listening.";
            boolean hasResult = false;
            for (String line : out.split("\\R")) {
                String t = line.trim();
                if (t.startsWith("killed ") || t.startsWith("failed ")) { hasResult = true; break; }
            }
            if (!hasResult) return "Nothing is listening on port " + port + ".";
            // Also remove any internally-tracked dev servers that match the killed PIDs.
            for (String line : out.split("\\R")) {
                if (line.startsWith("killed ")) {
                    try {
                        long killed = Long.parseLong(line.split("\\s+")[1]);
                        running.remove(killed);
                    } catch (Exception ignored) {}
                }
            }
            return "Port " + port + ":\n" + out;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "Stop a dev server started by startDevServer. Kills the process by PID.")
    public String stopDevServer(
            @ToolParam(description = "PID returned by startDevServer") long pid) {
        try {
            Map<String, Object> info = running.remove(pid);
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command",
                    "Stop-Process -Id " + pid + " -Force -ErrorAction SilentlyContinue; "
                    + "Get-Process -Id " + pid + " -ErrorAction SilentlyContinue | Out-Null; "
                    + "if ($?) { 'still-running' } else { 'stopped' }")
                    .redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor();
            return "PID " + pid + " → " + out
                    + (info == null ? " (was not in tracked list)" : "");
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Read the tail of the boot log and pull out a summary the user / bot can
     * actually act on: startup confirmation, real bound port, and — if we can
     * reach the root URL — whether "/" returns a useful page or the Spring Boot
     * Whitelabel 404. Saves the user from "I visited / and saw a 404, is the
     * server broken?" confusion.
     */
    private String summarizeBootLog(Path logOut, int hintPort) {
        try {
            if (!Files.exists(logOut)) return "";
            List<String> all = Files.readAllLines(logOut, StandardCharsets.UTF_8);
            StringBuilder out = new StringBuilder();
            // Scan for Spring Boot's "Started XxxApplication in N seconds" + port-bound lines.
            String started = null;
            String tomcatPort = null;
            String nettyPort = null;
            int tail = Math.max(0, all.size() - 200);
            java.util.regex.Pattern pStarted = java.util.regex.Pattern.compile(
                    "Started\\s+(\\S+)\\s+in\\s+([\\d\\.]+)\\s+seconds");
            java.util.regex.Pattern pTomcat = java.util.regex.Pattern.compile(
                    "Tomcat\\s+started\\s+on\\s+port\\s+(\\d+)|Tomcat\\s+started\\s+on\\s+port\\(s\\):\\s*(\\d+)");
            java.util.regex.Pattern pNetty = java.util.regex.Pattern.compile(
                    "Netty\\s+started\\s+on\\s+port\\s+(\\d+)");
            for (int i = tail; i < all.size(); i++) {
                String l = all.get(i);
                java.util.regex.Matcher m;
                if (started == null && (m = pStarted.matcher(l)).find()) {
                    started = m.group(1) + " (" + m.group(2) + "s)";
                }
                if (tomcatPort == null && (m = pTomcat.matcher(l)).find()) {
                    tomcatPort = m.group(1) != null ? m.group(1) : m.group(2);
                }
                if (nettyPort == null && (m = pNetty.matcher(l)).find()) {
                    nettyPort = m.group(1);
                }
            }
            int boundPort = hintPort > 0 ? hintPort
                    : (tomcatPort != null ? Integer.parseInt(tomcatPort)
                    : (nettyPort != null ? Integer.parseInt(nettyPort) : 0));

            if (started != null) out.append("  started: ").append(started).append('\n');
            if (tomcatPort != null) out.append("  bound:   Tomcat :").append(tomcatPort).append('\n');
            else if (nettyPort != null) out.append("  bound:   Netty :").append(nettyPort).append('\n');

            // Probe the root URL. If it returns 404 + Whitelabel, warn the user —
            // that's the single most common "is my app broken?" question and it's usually not.
            if (boundPort > 0) {
                String probe = probeRoot(boundPort);
                if (probe != null) out.append(probe);
            }
            return out.length() == 0 ? "" : "── runtime ──\n" + out.toString().stripTrailing();
        } catch (Exception e) {
            log.debug("[DevServer] summarizeBootLog failed: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Hit localhost:port/ and report whether it returns a useful page or the
     * Spring Boot Whitelabel 404. Returns a human-readable line, or null if
     * the server isn't reachable yet.
     */
    private String probeRoot(int port) {
        try {
            java.net.http.HttpClient c = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(2))
                    .build();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://localhost:" + port + "/"))
                    .timeout(java.time.Duration.ofSeconds(3))
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> resp = c.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            String body = resp.body() == null ? "" : resp.body();
            StringBuilder sb = new StringBuilder();
            sb.append("  GET /   : HTTP ").append(code);
            String url = "http://localhost:" + port + "/";
            if (code == 200) {
                sb.append(" ✓ (").append(body.length()).append(" bytes)\n");
                notifier.notify("🚀 APP IS READY — open " + url);
            } else if (code == 404 && body.contains("Whitelabel Error Page")) {
                sb.append(" ⚠ Whitelabel 404 — no route for `/`.\n");
                sb.append("  hint:    No controller maps GET `/`. To serve a home page either\n")
                  .append("           (a) drop an HTML file at src/main/resources/static/index.html (served at `/` automatically), or\n")
                  .append("           (b) add @GetMapping(\"/\") to a controller.\n")
                  .append("           Your app is FINE — this is just a missing root route, not a bug.\n");
                notifier.notify("🚀 App is up on :" + port + " — no `/` route (404 at root). Specific endpoints still work.");
            } else {
                sb.append(" (").append(body.length()).append(" bytes)\n");
                notifier.notify("🚀 App responded on :" + port + " with HTTP " + code);
            }
            return sb.toString();
        } catch (Exception e) {
            return null; // server not reachable — caller already reported other state
        }
    }

    /**
     * Write {@code server.port=N} into the project's application.properties so the
     * next Spring Boot launch binds to the requested port. Preserves other entries.
     * Creates the file if missing.
     */
    private String injectSpringPort(Path dir, int port) {
        try {
            Path propsFile = dir.resolve("src/main/resources/application.properties");
            if (!Files.exists(propsFile)) {
                Files.createDirectories(propsFile.getParent());
                Files.writeString(propsFile, "server.port=" + port + "\n", StandardCharsets.UTF_8);
                log.info("[DevServer] Created {} with server.port={}", propsFile, port);
                return "Created " + propsFile + " with server.port=" + port + ".";
            }
            List<String> lines = Files.readAllLines(propsFile, StandardCharsets.UTF_8);
            boolean replaced = false;
            for (int i = 0; i < lines.size(); i++) {
                String t = lines.get(i).trim();
                if (t.startsWith("server.port") && t.contains("=")) {
                    lines.set(i, "server.port=" + port);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) lines.add("server.port=" + port);
            Files.writeString(propsFile, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
            log.info("[DevServer] Updated {} → server.port={}", propsFile, port);
            return (replaced ? "Updated " : "Appended ") + "server.port=" + port + " in " + propsFile + ".";
        } catch (Exception e) {
            log.warn("[DevServer] injectSpringPort failed: {}", e.getMessage());
            return "Failed to set port " + port + ": " + e.getMessage();
        }
    }

    /**
     * Wait for a dev server to become ready. Bails early on any of:
     *   - process died (not alive) → returns false
     *   - port (if provided) accepts a TCP connection → returns true
     *   - log contains "Started" (Spring Boot's "Started X in N.N seconds" marker) → true
     *   - log contains "Listening on" / "running at" / "server started" (generic markers) → true
     * Otherwise keeps polling every 2s until {@code timeoutSec} elapses,
     * then returns the current alive state.
     */
    private boolean pollServerReady(long pid, Path logOut, Path logErr, int port, int timeoutSec) {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try { Thread.sleep(2000); } catch (InterruptedException e) { return isPidAlive(pid); }
            if (!isPidAlive(pid)) return false;
            if (port > 0 && isPortBound(port)) return true;
            // Scan the log every iteration for a "ready" marker.
            String tail = tailFile(logOut, 80).toLowerCase(Locale.ROOT);
            if (!tail.isEmpty()) {
                if (tail.contains("started ") && tail.contains(" seconds")) return true;
                if (tail.contains("listening on")) return true;
                if (tail.contains("running at http")) return true;
                if (tail.contains("server started")) return true;
                if (tail.contains("ready in ") && tail.contains("ms")) return true; // Vite / Next
            }
            // If the err log is substantial and process died, we'll catch it next iteration.
        }
        // Final state: if the process is still alive after the timeout, call it "running"
        // but the tail will tell the user whether it's actually bound.
        return isPidAlive(pid);
    }

    private boolean isPortBound(int port) {
        try {
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command",
                    "if (Test-NetConnection -ComputerName localhost -Port " + port + " -InformationLevel Quiet -WarningAction SilentlyContinue) { 'yes' } else { 'no' }")
                    .redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor();
            return out.endsWith("yes") || out.contains("True");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isPidAlive(long pid) {
        try {
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command",
                    "if (Get-Process -Id " + pid + " -ErrorAction SilentlyContinue) { 'yes' } else { 'no' }")
                    .redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor();
            return out.contains("yes");
        } catch (Exception e) {
            return false;
        }
    }

    private String tailFile(Path file, int lines) {
        try {
            if (!Files.exists(file)) return "";
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (all.isEmpty()) return "";
            int from = Math.max(0, all.size() - lines);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < all.size(); i++) sb.append(all.get(i)).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Expose the presets so docs / UI can list them. */
    public static Map<String, String> presets() { return PRESETS; }
}
