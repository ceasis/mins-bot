package com.minsbot.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * Detects system-wide user idle time via Windows GetLastInputInfo.
 * When idle exceeds the configured threshold, pushes a playful
 * "taking over" message to the chat. Resets when the user is active again.
 *
 * Config from ~/mins_bot_data/minsbot_config.txt section "## Idle detection":
 *   - enabled: true/false
 *   - idle_seconds: 300
 */
@Component
public class IdleDetectionService {

    private static final Logger log = LoggerFactory.getLogger(IdleDetectionService.class);

    private static final Path CONFIG_PATH =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "minsbot_config.txt");

    private static final String IDLE_MESSAGE =
            "No activity detected... I'm taking over the PC in 10 seconds! \uD83D\uDE08";

    private final AsyncMessageService asyncMessages;

    private volatile boolean enabled = true;
    private volatile int idleThresholdSeconds = 300;
    private volatile boolean messageSent = false;

    /** Reusable PS1 script file — created once, avoids recompiling C# Add-Type every call. */
    private Path idleScript;

    public IdleDetectionService(AsyncMessageService asyncMessages) {
        this.asyncMessages = asyncMessages;
    }

    @PostConstruct
    public void init() {
        loadConfigFromFile();
        createIdleScript();
    }

    @PreDestroy
    public void cleanup() {
        try {
            if (idleScript != null) Files.deleteIfExists(idleScript);
        } catch (IOException ignored) {}
    }

    /** Re-read config. Called by ConfigScanService when minsbot_config.txt changes. */
    public void reloadConfig() {
        loadConfigFromFile();
        log.info("[IdleDetection] Config reloaded — enabled={}, threshold={}s", enabled, idleThresholdSeconds);
    }

    @Scheduled(fixedDelay = 15000)
    public void check() {
        if (!enabled || !isWindows()) return;

        int idleSeconds = getSystemIdleSeconds();
        if (idleSeconds < 0) return; // failed to read

        if (idleSeconds >= idleThresholdSeconds && !messageSent) {
            asyncMessages.push(IDLE_MESSAGE);
            messageSent = true;
            log.info("[IdleDetection] User idle for {}s — sent takeover message", idleSeconds);
        } else if (idleSeconds < idleThresholdSeconds && messageSent) {
            messageSent = false;
            log.debug("[IdleDetection] User active again — reset");
        }
    }

    // ═══ System idle via PowerShell ═══

    private void createIdleScript() {
        if (!isWindows()) return;
        try {
            idleScript = Files.createTempFile("mins_bot_idle_", ".ps1");
            String script = """
                    Add-Type -TypeDefinition '
                    using System; using System.Runtime.InteropServices;
                    public class IdleCheck {
                        [DllImport("user32.dll")] static extern bool GetLastInputInfo(ref LASTINPUTINFO p);
                        public struct LASTINPUTINFO { public uint cbSize; public uint dwTime; }
                        public static int Seconds() {
                            var i = new LASTINPUTINFO { cbSize = 8 };
                            GetLastInputInfo(ref i);
                            return (Environment.TickCount - (int)i.dwTime) / 1000;
                        }
                    }'
                    [IdleCheck]::Seconds()
                    """;
            Files.writeString(idleScript, script, StandardCharsets.UTF_8);
            log.debug("[IdleDetection] Idle script created: {}", idleScript);
        } catch (IOException e) {
            log.warn("[IdleDetection] Failed to create idle script: {}", e.getMessage());
        }
    }

    private int getSystemIdleSeconds() {
        if (idleScript == null || !Files.exists(idleScript)) return -1;
        try {
            Process p = new ProcessBuilder(
                    "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", idleScript.toString())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            boolean done = p.waitFor(10, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return -1;
            }
            return Integer.parseInt(output);
        } catch (Exception e) {
            log.debug("[IdleDetection] Failed to get idle time: {}", e.getMessage());
            return -1;
        }
    }

    // ═══ Config ═══

    private void loadConfigFromFile() {
        if (!Files.exists(CONFIG_PATH)) return;
        try {
            String currentSection = "";
            for (String line : Files.readAllLines(CONFIG_PATH)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("## ")) {
                    currentSection = trimmed.toLowerCase();
                    continue;
                }
                if (!currentSection.equals("## idle detection")) continue;
                if (!trimmed.startsWith("- ")) continue;

                String kv = trimmed.substring(2).trim();
                int colon = kv.indexOf(':');
                if (colon < 0) continue;
                String key = kv.substring(0, colon).trim().toLowerCase();
                String val = kv.substring(colon + 1).trim().toLowerCase();

                switch (key) {
                    case "enabled" -> enabled = val.equals("true");
                    case "idle_seconds" -> {
                        try { idleThresholdSeconds = Integer.parseInt(val); }
                        catch (NumberFormatException ignored) {}
                    }
                }
            }
        } catch (IOException e) {
            log.warn("[IdleDetection] Could not read config: {}", e.getMessage());
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
