package com.minsbot.agent;

import com.minsbot.FloatingAppLauncher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Scans all ~/mins_bot_data/ config files every 15 seconds.
 * When a file changes, notifies the relevant service.
 *
 * Monitored files:
 * - minsbot_config.txt  → WorkingSoundService (sound) + IdleDetectionService (idle) + ScreenMemoryService (ocr)
 * - personal_config.md → logged (AI picks it up on next chat)
 * - cron_config.md     → logged (AI picks it up on next chat)
 * - system_config.md   → logged (AI picks it up on next chat)
 */
@Component
public class ConfigScanService {

    private static final Logger log = LoggerFactory.getLogger(ConfigScanService.class);

    private static final Path DATA_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data");

    private static final String[] WATCHED_FILES = {
            "minsbot_config.txt",
            "personal_config.md",
            "cron_config.md",
            "system_config.md"
    };

    private final WorkingSoundService workingSound;
    private final IdleDetectionService idleDetection;
    private final ScreenMemoryService screenMemory;
    private final AudioMemoryService audioMemory;

    /** Last-modified timestamp for each file, used to detect changes. */
    private final Map<String, FileTime> lastModified = new LinkedHashMap<>();

    public ConfigScanService(WorkingSoundService workingSound,
                             IdleDetectionService idleDetection,
                             ScreenMemoryService screenMemory,
                             AudioMemoryService audioMemory) {
        this.workingSound = workingSound;
        this.idleDetection = idleDetection;
        this.screenMemory = screenMemory;
        this.audioMemory = audioMemory;
    }

    @PostConstruct
    public void init() {
        // Record initial timestamps (no change logs on first scan)
        for (String file : WATCHED_FILES) {
            Path path = DATA_DIR.resolve(file);
            if (Files.exists(path)) {
                try {
                    lastModified.put(file, Files.getLastModifiedTime(path));
                } catch (IOException ignored) {}
            }
        }
    }

    @Scheduled(fixedDelay = 15000)
    public void scan() {
        for (String file : WATCHED_FILES) {
            Path path = DATA_DIR.resolve(file);
            if (!Files.exists(path)) continue;
            try {
                FileTime current = Files.getLastModifiedTime(path);
                FileTime previous = lastModified.get(file);
                if (previous != null && current.equals(previous)) continue;

                lastModified.put(file, current);

                // Only log after initial scan (previous != null means we've seen it before)
                if (previous != null) {
                    log.info("[ConfigScan] {} changed — reloading", file);
                    onFileChanged(file);
                }
            } catch (IOException ignored) {}
        }
    }

    private void onFileChanged(String file) {
        switch (file) {
            case "minsbot_config.txt" -> {
                workingSound.reloadConfig();
                idleDetection.reloadConfig();
                screenMemory.reloadConfig();
                audioMemory.reloadConfig();
                FloatingAppLauncher.refreshBotName();
            }
            // personal_config.md, cron_config.md, system_config.md:
            // These are re-read by SystemContextProvider on every chat request,
            // so the AI automatically sees changes. Just log for visibility.
            default -> log.info("[ConfigScan] {} will be picked up on next chat message", file);
        }
    }
}
