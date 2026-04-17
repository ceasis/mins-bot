package com.minsbot.agent;

import com.minsbot.FloatingAppLauncher;
import com.minsbot.agent.tools.ScreenClickTools;
import com.minsbot.agent.tools.TtsTools;
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
 * - personal_config.txt → logged (AI picks it up on next chat)
 * - cron_config.txt     → logged (AI picks it up on next chat)
 * - system_config.txt   → logged (AI picks it up on next chat)
 */
@Component
public class ConfigScanService {

    private static final Logger log = LoggerFactory.getLogger(ConfigScanService.class);

    private static final Path DATA_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data");

    private static final String[] WATCHED_FILES = {
            "minsbot_config.txt",
            "personal_config.txt",
            "cron_config.txt",
            "system_config.txt",
            "system-prompt.md"
    };

    private final WorkingSoundService workingSound;
    private final IdleDetectionService idleDetection;
    private final ScreenMemoryService screenMemory;
    private final AudioMemoryService audioMemory;
    private final WebcamMemoryService webcamMemory;
    private final TtsTools ttsTools;
    private final PlaylistService playlistService;
    private final ScreenClickTools screenClickTools;
    private final SystemPromptService systemPromptService;

    /** Last-modified timestamp for each file, used to detect changes. */
    private final Map<String, FileTime> lastModified = new LinkedHashMap<>();

    /** Last-modified snapshot of every .md file in skills/ — detects adds/edits/renames. */
    private volatile long skillsDirLastScanMs = 0;
    private final Map<String, FileTime> skillFileTimes = new LinkedHashMap<>();

    public ConfigScanService(WorkingSoundService workingSound,
                             IdleDetectionService idleDetection,
                             ScreenMemoryService screenMemory,
                             AudioMemoryService audioMemory,
                             WebcamMemoryService webcamMemory,
                             TtsTools ttsTools,
                             PlaylistService playlistService,
                             ScreenClickTools screenClickTools,
                             SystemPromptService systemPromptService) {
        this.workingSound = workingSound;
        this.idleDetection = idleDetection;
        this.screenMemory = screenMemory;
        this.audioMemory = audioMemory;
        this.webcamMemory = webcamMemory;
        this.ttsTools = ttsTools;
        this.playlistService = playlistService;
        this.screenClickTools = screenClickTools;
        this.systemPromptService = systemPromptService;
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
        scanSkillsDirectory();
    }

    /** Watches ~/mins_bot_data/skills/*.md for adds, removes, and edits. */
    private void scanSkillsDirectory() {
        Path skillsDir = SystemPromptService.SKILLS_DIR;
        if (!Files.isDirectory(skillsDir)) return;

        Map<String, FileTime> current = new LinkedHashMap<>();
        try (java.nio.file.DirectoryStream<Path> stream =
                     Files.newDirectoryStream(skillsDir, "*.md")) {
            for (Path file : stream) {
                try {
                    current.put(file.getFileName().toString(), Files.getLastModifiedTime(file));
                } catch (IOException ignored) {}
            }
        } catch (IOException e) {
            return;
        }

        boolean firstScan = skillsDirLastScanMs == 0;
        skillsDirLastScanMs = System.currentTimeMillis();

        if (firstScan) {
            skillFileTimes.putAll(current);
            return;
        }

        boolean changed = !current.equals(skillFileTimes);
        if (changed) {
            log.info("[ConfigScan] skills/ directory changed — invalidating skill cache");
            skillFileTimes.clear();
            skillFileTimes.putAll(current);
            systemPromptService.reloadSkills();
        }
    }

    private void onFileChanged(String file) {
        switch (file) {
            case "minsbot_config.txt" -> {
                workingSound.reloadConfig();
                idleDetection.reloadConfig();
                screenMemory.reloadConfig();
                audioMemory.reloadConfig();
                webcamMemory.reloadConfig();
                ttsTools.reloadConfig();
                playlistService.reloadConfig();
                screenClickTools.reloadConfig();
                FloatingAppLauncher.refreshBotName();
            }
            case "system-prompt.md" -> systemPromptService.reloadTemplate();
            // personal_config.txt, cron_config.txt, system_config.txt:
            // These are re-read by SystemContextProvider on every chat request,
            // so the AI automatically sees changes. Just log for visibility.
            default -> log.info("[ConfigScan] {} will be picked up on next chat message", file);
        }
    }
}
