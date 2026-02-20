package com.botsfer;

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

    @Value("${app.screenshot.interval-seconds:5}")
    private int intervalSeconds;

    @Value("${app.screenshot.enabled:true}")
    private boolean enabled;

    @Value("${app.screenshot.max-age-days:3}")
    private int maxAgeDays;

    private Path screenshotDir;
    private ScheduledExecutorService scheduler;
    private Robot robot;

    @PostConstruct
    public void init() throws IOException {
        screenshotDir = Paths.get(System.getProperty("user.home"), "botsfer_data", "screenshots");
        Files.createDirectories(screenshotDir);
        log.info("Screenshot directory: {}", screenshotDir);

        if (!enabled) {
            log.info("Screenshot capture is disabled");
            return;
        }

        try {
            robot = new Robot();
        } catch (AWTException e) {
            log.error("Cannot create Robot for screenshots", e);
            return;
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "screenshot-worker");
            t.setDaemon(true);
            return t;
        });
        cleanupOldScreenshots();
        scheduler.scheduleAtFixedRate(this::capture, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        // Run cleanup once a day
        scheduler.scheduleAtFixedRate(this::cleanupOldScreenshots, 1L, 1L, TimeUnit.DAYS);
        log.info("Screenshot capture started (every {}s, cleanup after {}d)", intervalSeconds, maxAgeDays);
    }

    @PreDestroy
    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void cleanupOldScreenshots() {
        try {
            Instant cutoff = Instant.now().minus(maxAgeDays, ChronoUnit.DAYS);
            int deleted = 0;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(screenshotDir, "*.png")) {
                for (Path file : stream) {
                    if (Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(file);
                        deleted++;
                    }
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
            String filename = LocalDateTime.now().format(FMT) + ".png";
            Path file = screenshotDir.resolve(filename);
            ImageIO.write(image, "png", file.toFile());
        } catch (Exception e) {
            log.error("Screenshot capture failed", e);
        }
    }
}
