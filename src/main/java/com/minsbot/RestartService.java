package com.minsbot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Self-restart: writes a tiny detached helper script that waits for this
 * process to exit, then relaunches the bot with the original command line.
 * <p>
 * Command resolution priority:
 * <ol>
 *   <li>{@code app.restart.command} property (explicit override, e.g. {@code mvn spring-boot:run})</li>
 *   <li>{@code ProcessHandle.current().info().commandLine()} — the original JVM invocation</li>
 * </ol>
 */
@Service
public class RestartService {

    private static final Logger log = LoggerFactory.getLogger(RestartService.class);

    private final MinsBotQuitService quitService;

    @Value("${app.restart.command:}")
    private String configuredCommand;

    public RestartService(MinsBotQuitService quitService) {
        this.quitService = quitService;
    }

    /**
     * Writes a helper script that waits ~3 seconds (for this process to die) then
     * relaunches with the resolved command. Then requests quit. Returns a user-facing
     * status string.
     */
    public String restart() {
        String command = resolveLaunchCommand();
        if (command == null || command.isBlank()) {
            return "Cannot restart — couldn't determine the original launch command. "
                    + "Set `app.restart.command` in application.properties (e.g. `mvn spring-boot:run`) "
                    + "and try again.";
        }

        String workingDir = System.getProperty("user.dir", ".");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");

        try {
            Path script = windows
                    ? writeWindowsHelper(command, workingDir)
                    : writeUnixHelper(command, workingDir);

            ProcessBuilder pb = windows
                    ? new ProcessBuilder("cmd", "/c", "start", "", script.toString())
                    : new ProcessBuilder("/bin/sh", script.toString());
            pb.directory(new java.io.File(workingDir));
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            pb.start();

            log.info("[Restart] Helper launched ({}). Quitting in 1 second...", script);

            // Delay briefly so the helper process is firmly detached before we exit.
            new Thread(() -> {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                quitService.requestQuit();
            }, "restart-trigger").start();

            return "Restarting in ~3 seconds... I'll be right back.";
        } catch (IOException e) {
            log.error("[Restart] Failed: {}", e.getMessage(), e);
            return "Restart failed: " + e.getMessage();
        }
    }

    /** For diagnostics — show what command would be used without restarting. */
    public String previewCommand() {
        String cmd = resolveLaunchCommand();
        if (cmd == null) return "(no command could be resolved)";
        String source = configuredCommand != null && !configuredCommand.isBlank()
                ? "from app.restart.command" : "from ProcessHandle";
        return "Would relaunch with (" + source + "):\n  " + cmd;
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private String resolveLaunchCommand() {
        if (configuredCommand != null && !configuredCommand.isBlank()) {
            return configuredCommand.trim();
        }
        Optional<String> cmd = ProcessHandle.current().info().commandLine();
        return cmd.orElse(null);
    }

    private Path writeWindowsHelper(String command, String workingDir) throws IOException {
        long pid = ProcessHandle.current().pid();
        // Wait for our PID to exit, then relaunch.
        // Use PowerShell for reliable PID-wait + `start` for detached launch.
        String body = """
                @echo off
                powershell -NoProfile -Command "try { Wait-Process -Id %d -Timeout 30 -ErrorAction SilentlyContinue } catch {}"
                timeout /t 1 /nobreak > nul
                cd /d "%s"
                start "" %s
                exit
                """.formatted(pid, workingDir.replace("\"", "\\\""), command);

        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"));
        Path script = tempDir.resolve("minsbot-restart-" + pid + ".bat");
        Files.writeString(script, body, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return script;
    }

    private Path writeUnixHelper(String command, String workingDir) throws IOException {
        long pid = ProcessHandle.current().pid();
        String body = """
                #!/bin/sh
                # Wait up to 30s for the parent bot process to exit, then relaunch.
                for i in $(seq 1 60); do
                  if ! kill -0 %d 2>/dev/null; then break; fi
                  sleep 0.5
                done
                sleep 1
                cd "%s"
                nohup %s >/dev/null 2>&1 &
                """.formatted(pid, workingDir.replace("\"", "\\\""), command);

        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"));
        Path script = tempDir.resolve("minsbot-restart-" + pid + ".sh");
        Files.writeString(script, body, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(script, perms);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem — the helper will be invoked explicitly via /bin/sh anyway.
        }
        return script;
    }

    // Keeps imports lean; helper reserved for future multi-command builds.
    @SuppressWarnings("unused")
    private List<String> splitCommand(String command) {
        // ProcessBuilder expects an argv list, but we let the shell handle splitting
        // via `start` (Windows) or `sh -c` (Unix). This helper is here for completeness.
        List<String> out = new ArrayList<>();
        out.add(command);
        return out;
    }
}
