package com.minsbot.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.util.Random;

/**
 * Mouse movement with human-like 10px-per-step motion, and background screen
 * monitoring via a 200ms ring buffer of in-memory screenshots for pixel-level
 * change detection. No LLM calls — pure Java Robot + pixel comparison.
 */
@Service
public class MouseModule {

    private static final Logger log = LoggerFactory.getLogger(MouseModule.class);

    // Ring buffer: 5 frames, 200ms apart = 1 second window
    private static final int BUFFER_SIZE = 5;
    private static final int CAPTURE_INTERVAL_MS = 200;
    private static final int STEP_SIZE = 10; // pixels per step
    private static final double CHANGE_THRESHOLD = 1.0; // % pixels changed to count as "screen changed"
    private static final int COLOR_THRESHOLD = 30; // RGB diff to count as changed pixel

    private final BufferedImage[] ringBuffer = new BufferedImage[BUFFER_SIZE];
    private volatile int writeIndex = 0;
    private volatile long lastCaptureTime = 0;
    private volatile boolean running = true;
    private Thread captureThread;
    private Robot robot;
    private Dimension screenSize;
    private final Random random = new Random();

    @PostConstruct
    void init() {
        try {
            robot = new Robot();
            screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        } catch (AWTException e) {
            log.error("[MouseModule] Failed to create Robot: {}", e.getMessage());
            return;
        }

        captureThread = new Thread(this::captureLoop, "mouse-module-capture");
        captureThread.setDaemon(true);
        captureThread.start();
        log.info("[MouseModule] Started — screen {}x{}, ring buffer {}x{}ms",
                screenSize.width, screenSize.height, BUFFER_SIZE, CAPTURE_INTERVAL_MS);
    }

    @PreDestroy
    void shutdown() {
        running = false;
        if (captureThread != null) captureThread.interrupt();
        log.info("[MouseModule] Stopped");
    }

    // ═══ Capture daemon ═══

    private void captureLoop() {
        while (running) {
            try {
                Rectangle rect = new Rectangle(screenSize);
                BufferedImage frame = robot.createScreenCapture(rect);
                // Do NOT draw cursor — this is for pixel comparison, not vision API
                ringBuffer[writeIndex] = frame;
                writeIndex = (writeIndex + 1) % BUFFER_SIZE;
                lastCaptureTime = System.currentTimeMillis();
                Thread.sleep(CAPTURE_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("[MouseModule] Capture error: {}", e.getMessage());
                try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
            }
        }
    }

    // ═══ Mouse Movement ═══

    /**
     * Move one step (~10px) toward the target. Does NOT loop — caller controls pacing.
     * Adds ±1px random jitter for human-like movement.
     * @return true if arrived (within 2px of target), false if still moving
     */
    public boolean moveToward(int targetX, int targetY) {
        if (robot == null) return true;
        Point current = MouseInfo.getPointerInfo().getLocation();
        int dx = targetX - current.x;
        int dy = targetY - current.y;
        double distance = Math.sqrt(dx * dx + dy * dy);

        if (distance <= 2) {
            robot.mouseMove(targetX, targetY);
            return true; // arrived
        }

        int stepX, stepY;
        if (distance <= STEP_SIZE) {
            stepX = dx;
            stepY = dy;
        } else {
            stepX = (int) Math.round(dx * STEP_SIZE / distance);
            stepY = (int) Math.round(dy * STEP_SIZE / distance);
        }

        // Human-like jitter: ±1px
        stepX += random.nextInt(3) - 1;
        stepY += random.nextInt(3) - 1;

        int newX = Math.max(0, Math.min(screenSize.width - 1, current.x + stepX));
        int newY = Math.max(0, Math.min(screenSize.height - 1, current.y + stepY));
        robot.mouseMove(newX, newY);

        // Random delay between steps (15-25ms) to mimic human cadence
        robot.delay(15 + random.nextInt(11));
        return false;
    }

    /** Move one 10px step in a random direction. */
    public void moveRandom() {
        if (robot == null) return;
        double angle = random.nextDouble() * 2 * Math.PI;
        Point current = MouseInfo.getPointerInfo().getLocation();
        int newX = current.x + (int) (STEP_SIZE * Math.cos(angle));
        int newY = current.y + (int) (STEP_SIZE * Math.sin(angle));
        newX = Math.max(0, Math.min(screenSize.width - 1, newX));
        newY = Math.max(0, Math.min(screenSize.height - 1, newY));
        robot.mouseMove(newX, newY);
        robot.delay(15 + random.nextInt(11));
    }

    /** Move to target (10px per step) then click. Blocks until done. */
    public void click(int x, int y) {
        click(x, y, "left");
    }

    /** Move to target (10px per step) then click with specified button. Blocks until done. */
    public void click(int x, int y, String button) {
        if (robot == null) return;

        // Guard: don't click inside the bot window
        if (com.minsbot.FloatingAppLauncher.isInsideWindow(x, y)) {
            log.warn("[MouseModule] click blocked — target ({},{}) is inside bot window", x, y);
            return;
        }

        // Move to target
        int maxSteps = 500; // safety limit
        for (int i = 0; i < maxSteps; i++) {
            if (moveToward(x, y)) break;
        }

        // Small random delay before click (30-80ms) to mimic human hesitation
        robot.delay(30 + random.nextInt(51));

        // Click
        int mask = switch (button.toLowerCase()) {
            case "right" -> InputEvent.BUTTON3_DOWN_MASK;
            case "middle" -> InputEvent.BUTTON2_DOWN_MASK;
            default -> InputEvent.BUTTON1_DOWN_MASK;
        };
        robot.mousePress(mask);
        robot.delay(20 + random.nextInt(20));
        robot.mouseRelease(mask);
    }

    /** Double-click at target. */
    public void doubleClick(int x, int y) {
        if (robot == null) return;
        if (com.minsbot.FloatingAppLauncher.isInsideWindow(x, y)) return;

        for (int i = 0; i < 500; i++) {
            if (moveToward(x, y)) break;
        }
        robot.delay(30 + random.nextInt(30));
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.delay(60 + random.nextInt(40));
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    }

    // ═══ Screen Change Detection ═══

    /** Check if screen changed between the two most recent frames. */
    public boolean hasScreenChanged() {
        return getChangePercent() > CHANGE_THRESHOLD;
    }

    /** Get pixel change percentage between the two most recent frames. */
    public double getChangePercent() {
        int idx = writeIndex; // snapshot volatile read
        BufferedImage latest = ringBuffer[(idx - 1 + BUFFER_SIZE) % BUFFER_SIZE];
        BufferedImage previous = ringBuffer[(idx - 2 + BUFFER_SIZE) % BUFFER_SIZE];
        if (latest == null || previous == null) return 0.0;
        return compareFrames(latest, previous);
    }

    /** Get the most recent frame from the ring buffer. */
    public BufferedImage getLatestFrame() {
        int idx = writeIndex;
        return ringBuffer[(idx - 1 + BUFFER_SIZE) % BUFFER_SIZE];
    }

    /** Get a specific frame from the ring buffer (0 = most recent, 4 = oldest). */
    public BufferedImage getFrame(int ago) {
        if (ago < 0 || ago >= BUFFER_SIZE) return null;
        int idx = writeIndex;
        return ringBuffer[(idx - 1 - ago + BUFFER_SIZE * 2) % BUFFER_SIZE];
    }

    /** Capture a region in memory (no disk I/O). */
    public BufferedImage captureRegion(int centerX, int centerY, int width, int height) {
        if (robot == null) return null;
        int x = Math.max(0, centerX - width / 2);
        int y = Math.max(0, centerY - height / 2);
        int w = Math.min(width, screenSize.width - x);
        int h = Math.min(height, screenSize.height - y);
        return robot.createScreenCapture(new Rectangle(x, y, w, h));
    }

    // ═══ Pixel Comparison (public static utility) ═══

    /**
     * Compare two BufferedImages pixel-by-pixel. Returns percentage of pixels
     * that changed beyond the color threshold (RGB diff > 30).
     * Extracted from ScreenClickTools.compareSmallRegion().
     */
    public static double compareFrames(BufferedImage a, BufferedImage b) {
        if (a == null || b == null) return 0.0;
        int w = Math.min(a.getWidth(), b.getWidth());
        int h = Math.min(a.getHeight(), b.getHeight());
        if (w == 0 || h == 0) return 0.0;

        int total = w * h;
        int changed = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c1 = a.getRGB(x, y);
                int c2 = b.getRGB(x, y);
                if (c1 != c2) {
                    int dr = Math.abs(((c1 >> 16) & 0xFF) - ((c2 >> 16) & 0xFF));
                    int dg = Math.abs(((c1 >> 8) & 0xFF) - ((c2 >> 8) & 0xFF));
                    int db = Math.abs((c1 & 0xFF) - (c2 & 0xFF));
                    if (dr + dg + db > COLOR_THRESHOLD) {
                        changed++;
                    }
                }
            }
        }
        return (changed * 100.0) / total;
    }

    /** Whether the capture daemon is running. */
    public boolean isRunning() {
        return running && captureThread != null && captureThread.isAlive();
    }

    /** Time since last capture in ms. */
    public long timeSinceLastCapture() {
        return System.currentTimeMillis() - lastCaptureTime;
    }
}
