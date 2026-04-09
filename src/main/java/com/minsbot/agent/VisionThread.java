package com.minsbot.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.*;

/**
 * Single entry point for all LLM vision API calls. Serializes requests through
 * a single-thread executor to prevent parallel vision calls burning API credits.
 * Caches screenshots for reuse if less than 2 seconds old.
 */
@Service
public class VisionThread {

    private static final Logger log = LoggerFactory.getLogger(VisionThread.class);
    private static final long CACHE_TTL_MS = 2000;

    private final VisionService visionService;
    private final ModuleStatsService moduleStats;

    private final ExecutorService visionExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vision-thread");
        t.setDaemon(true);
        return t;
    });

    // Screenshot cache
    private volatile BufferedImage cachedScreenshot;
    private volatile long cachedScreenshotTime = 0;

    public VisionThread(VisionService visionService,
                        @org.springframework.beans.factory.annotation.Autowired(required = false)
                        ModuleStatsService moduleStats) {
        this.visionService = visionService;
        this.moduleStats = moduleStats;
        log.info("[VisionThread] Ready — single-thread executor, {}ms cache TTL", CACHE_TTL_MS);
    }

    @PreDestroy
    void shutdown() {
        visionExecutor.shutdownNow();
    }

    /**
     * Analyze the current screen with a vision LLM. Returns the AI's response.
     * Queued through single-thread executor — only one vision call at a time.
     * Screenshot is cached and reused if <2 seconds old.
     */
    public CompletableFuture<String> analyzeScreen(String question) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                byte[] imageBytes = getOrCaptureScreenshotBytes();
                if (imageBytes == null) return null;

                // Try GPT Vision
                if (visionService.isAvailable()) {
                    String result = visionService.analyzeWithPrompt(imageBytes, question);
                    if (result != null && !result.isBlank()) return result;
                }

                log.warn("[VisionThread] Vision service returned empty");
                return null;
            } catch (Exception e) {
                log.warn("[VisionThread] analyzeScreen failed: {}", e.getMessage());
                return null;
            }
        }, visionExecutor);
    }

    /**
     * Find an element on screen by description. Returns [x, y] coordinates or null.
     * Queued through single-thread executor.
     */
    public CompletableFuture<int[]> findElement(String description) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                BufferedImage screenshot = getOrCaptureScreenshot();
                if (screenshot == null) return null;
                Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
                byte[] imageBytes = toBytes(screenshot);
                if (imageBytes == null) return null;

                // Build coordinate-finding prompt
                String prompt = "You are a pixel-precise UI element locator. "
                        + "Find the element '" + description + "' in this screenshot and return "
                        + "the CENTER coordinates.\n\n"
                        + "Image dimensions: " + screen.width + "x" + screen.height + " pixels.\n"
                        + "Return ONLY: COORDS:x,y (integers)\n"
                        + "If not found: NOT_FOUND";

                String response = null;
                if (visionService.isAvailable()) {
                    response = visionService.analyzeWithPrompt(imageBytes, prompt);
                }
                if (response == null || response.isBlank() || response.contains("NOT_FOUND")) return null;

                // Parse COORDS:x,y
                for (String line : response.trim().split("\\r?\\n")) {
                    line = line.trim();
                    if (line.startsWith("COORDS:")) {
                        String[] parts = line.substring(7).split(",");
                        if (parts.length == 2) {
                            int x = Integer.parseInt(parts[0].trim());
                            int y = Integer.parseInt(parts[1].trim());
                            // Check for normalized 0-1000 scale
                            if (x <= 1000 && y <= 1000 && (screen.width > 1000 || screen.height > 1000)) {
                                x = (int) Math.round(x * screen.width / 1000.0);
                                y = (int) Math.round(y * screen.height / 1000.0);
                            }
                            if (x >= 0 && x <= screen.width && y >= 0 && y <= screen.height) {
                                return new int[]{x, y};
                            }
                        }
                    }
                }
                return null;
            } catch (Exception e) {
                log.warn("[VisionThread] findElement failed: {}", e.getMessage());
                return null;
            }
        }, visionExecutor);
    }

    /** Capture a fresh screenshot in memory (no disk). Can be called from any thread. */
    public BufferedImage captureScreenshotNow() {
        try {
            Robot r = new Robot();
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            BufferedImage img = r.createScreenCapture(new Rectangle(screen));
            SystemControlService.drawCursorOnImage(img, 0, 0);
            return img;
        } catch (Exception e) {
            log.warn("[VisionThread] captureScreenshotNow failed: {}", e.getMessage());
            return null;
        }
    }

    // ═══ Internal helpers ═══

    private BufferedImage getOrCaptureScreenshot() {
        long now = System.currentTimeMillis();
        if (cachedScreenshot != null && (now - cachedScreenshotTime) < CACHE_TTL_MS) {
            log.debug("[VisionThread] Reusing cached screenshot ({}ms old)", now - cachedScreenshotTime);
            return cachedScreenshot;
        }
        try {
            Robot r = new Robot();
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            BufferedImage img = r.createScreenCapture(new Rectangle(screen));
            SystemControlService.drawCursorOnImage(img, 0, 0);
            cachedScreenshot = img;
            cachedScreenshotTime = System.currentTimeMillis();
            return img;
        } catch (Exception e) {
            log.warn("[VisionThread] Screenshot capture failed: {}", e.getMessage());
            return null;
        }
    }

    private byte[] getOrCaptureScreenshotBytes() {
        BufferedImage img = getOrCaptureScreenshot();
        return toBytes(img);
    }

    private byte[] toBytes(BufferedImage img) {
        if (img == null) return null;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            log.warn("[VisionThread] Image to bytes failed: {}", e.getMessage());
            return null;
        }
    }
}
