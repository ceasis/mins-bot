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
        screenshotDir = Paths.get(System.getProperty("user.home"), "mins_bot_data", "screenshots");
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

            // Draw mouse cursor onto the screenshot
            try {
                Point mousePos = MouseInfo.getPointerInfo().getLocation();
                Graphics2D g = image.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int mx = mousePos.x, my = mousePos.y;
                int[] cx = {mx, mx, mx + 10, mx + 6, mx + 8, mx + 5, mx + 5};
                int[] cy = {my, my + 17, my + 12, my + 12, my + 20, my + 15, my + 13};
                g.setColor(Color.WHITE);
                g.fillPolygon(cx, cy, cx.length);
                g.setColor(Color.BLACK);
                g.setStroke(new BasicStroke(1.2f));
                g.drawPolygon(cx, cy, cx.length);
                g.dispose();
            } catch (Exception ignored) { /* mouse position unavailable */ }

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
