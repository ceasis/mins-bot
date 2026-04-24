package com.minsbot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class ScreenshotService {

    private static final Logger log = LoggerFactory.getLogger(ScreenshotService.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter YEAR_MONTH_FMT = DateTimeFormatter.ofPattern("yyyy_MMM");
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("d");

    @Value("${app.screenshot.interval-seconds:5}")
    private volatile int intervalSeconds;

    @Value("${app.screenshot.enabled:true}")
    private volatile boolean enabled;

    @Value("${app.screenshot.max-age-days:3}")
    private volatile int maxAgeDays;

    private static final Path SETTINGS_PATH =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "screenshot_settings.txt");

    private Path screenshotDir;
    private volatile ScheduledExecutorService scheduler;
    private Robot robot;

    @PostConstruct
    public void init() throws IOException {
        screenshotDir = Paths.get(System.getProperty("user.home"), "mins_bot_data", "screenshots");
        Files.createDirectories(screenshotDir);
        log.info("Screenshot directory: {}", screenshotDir);
        loadPersistedSettings();
        startSchedulerIfEnabled();
    }

    @PreDestroy
    public void shutdown() {
        stopScheduler();
    }

    public boolean isEnabled()        { return enabled; }
    public int getIntervalSeconds()   { return intervalSeconds; }
    public int getMaxAgeDays()        { return maxAgeDays; }

    /** Turn capture on/off at runtime. Persists across restarts. */
    public synchronized void setEnabled(boolean on) {
        if (this.enabled == on) return;
        this.enabled = on;
        saveSettings();
        if (on) startSchedulerIfEnabled();
        else    stopScheduler();
    }

    /** Change capture cadence at runtime. Clamped to [1, 3600] sec. Persists. */
    public synchronized void setIntervalSeconds(int seconds) {
        int clamped = Math.max(1, Math.min(3600, seconds));
        if (clamped == this.intervalSeconds) return;
        this.intervalSeconds = clamped;
        saveSettings();
        if (enabled) {
            stopScheduler();
            startSchedulerIfEnabled();
        }
    }

    /** Retention window. Clamped to [1, 365]. Persists. Takes effect on next cleanup. */
    public synchronized void setMaxAgeDays(int days) {
        int clamped = Math.max(1, Math.min(365, days));
        if (clamped == this.maxAgeDays) return;
        this.maxAgeDays = clamped;
        saveSettings();
    }

    private void startSchedulerIfEnabled() {
        if (!enabled) { log.info("Screenshot capture is disabled"); return; }
        if (robot == null) {
            try { robot = new Robot(); }
            catch (AWTException e) { log.error("Cannot create Robot for screenshots", e); return; }
        }
        scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "screenshot-worker");
            t.setDaemon(true);
            return t;
        });
        cleanupOldScreenshots();
        scheduler.scheduleAtFixedRate(this::capture, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::cleanupOldScreenshots, 1L, 1L, TimeUnit.DAYS);
        log.info("Screenshot capture started (every {}s, cleanup after {}d)", intervalSeconds, maxAgeDays);
    }

    private void stopScheduler() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
            log.info("Screenshot capture stopped");
        }
    }

    private void loadPersistedSettings() {
        try {
            if (!Files.exists(SETTINGS_PATH)) return;
            for (String line : Files.readAllLines(SETTINGS_PATH)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                switch (k) {
                    case "enabled" -> this.enabled = Boolean.parseBoolean(v);
                    case "intervalSeconds" -> { try { this.intervalSeconds = Integer.parseInt(v); } catch (NumberFormatException ignored) {} }
                    case "maxAgeDays" -> { try { this.maxAgeDays = Integer.parseInt(v); } catch (NumberFormatException ignored) {} }
                }
            }
        } catch (IOException e) {
            log.warn("Failed to load screenshot settings: {}", e.getMessage());
        }
    }

    private void saveSettings() {
        try {
            Files.createDirectories(SETTINGS_PATH.getParent());
            String body = "# Screenshot settings (edited via UI)\n"
                    + "enabled=" + enabled + "\n"
                    + "intervalSeconds=" + intervalSeconds + "\n"
                    + "maxAgeDays=" + maxAgeDays + "\n";
            Files.writeString(SETTINGS_PATH, body);
        } catch (IOException e) {
            log.warn("Failed to save screenshot settings: {}", e.getMessage());
        }
    }

    private void cleanupOldScreenshots() {
        try {
            Instant cutoff = Instant.now().minus(maxAgeDays, ChronoUnit.DAYS);
            int deleted = 0;
            try (var walk = Files.walk(screenshotDir)) {
                var oldFiles = walk
                        .filter(p -> p.toString().endsWith(".png"))
                        .filter(p -> {
                            try { return Files.getLastModifiedTime(p).toInstant().isBefore(cutoff); }
                            catch (IOException e) { return false; }
                        })
                        .toList();
                for (Path file : oldFiles) {
                    Files.deleteIfExists(file);
                    deleted++;
                }
            }
            if (deleted > 0) {
                log.info("Cleaned up {} old screenshots (older than {}d)", deleted, maxAgeDays);
            }
        } catch (Exception e) {
            log.error("Screenshot cleanup failed", e);
        }
    }

    private void capture() {
        try {
            Rectangle screenRect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            BufferedImage image = robot.createScreenCapture(screenRect);

            com.minsbot.agent.SystemControlService.drawCursorOnImage(image, 0, 0);

            LocalDateTime now = LocalDateTime.now();
            Path dayDir = screenshotDir
                    .resolve(now.format(YEAR_MONTH_FMT))
                    .resolve(now.format(DAY_FMT));
            Files.createDirectories(dayDir);
            String filename = now.format(FMT) + ".png";
            Path file = dayDir.resolve(filename);
            ImageIO.write(image, "png", file.toFile());
        } catch (Exception e) {
            log.error("Screenshot capture failed", e);
        }
    }
}
