package com.minsbot;

import jakarta.annotation.PreDestroy;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Animates the taskbar/alt-tab icon by compositing moving pupils on top of
 * the static bot-icon PNGs. Pupils ease toward random targets, occasionally blink.
 */
@Service
public class IconAnimator {

    private static final Logger log = LoggerFactory.getLogger(IconAnimator.class);

    // Eye positions as fractions of icon width/height — tune if your icon differs.
    private static final double LEFT_EYE_X   = 0.34;
    private static final double LEFT_EYE_Y   = 0.44;
    private static final double RIGHT_EYE_X  = 0.66;
    private static final double RIGHT_EYE_Y  = 0.44;
    private static final double EYE_RADIUS   = 0.13;  // white area
    private static final double PUPIL_RADIUS = 0.06;
    private static final double PUPIL_RANGE  = 0.055; // max offset from eye center

    private static final int TICK_MS = 125;

    private BufferedImage base32, base64, base256;
    private Stage primaryStage;
    private ScheduledExecutorService scheduler;

    private double targetX = 0, targetY = 0;
    private double currentX = 0, currentY = 0;
    private long nextTargetAt = 0;
    private boolean blinking = false;
    private long blinkUntil = 0;
    private long nextBlinkAt = 0;
    private int pendingDoubleBlinks = 0;  // N more blinks queued (for double/triple blinks)

    // Mouse-follow state
    private Point lastMouse;
    private long lastMouseMoveAt = 0;
    private static final long FOLLOW_TIMEOUT_MS = 2500;  // stop following N ms after cursor stops

    public void start(Stage stage) {
        this.primaryStage = stage;
        try {
            base256 = load("/static/bot-icon.png");
            base64  = load("/static/bot-icon-64.png");
            base32  = load("/static/bot-icon-32.png");
        } catch (Exception e) {
            log.warn("[IconAnimator] Could not load base icons: {}", e.getMessage());
            return;
        }
        long now = System.currentTimeMillis();
        nextBlinkAt = now + 1500 + (long)(Math.random() * 2500);

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mins-icon-anim");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::tick, 200, TICK_MS, TimeUnit.MILLISECONDS);
        log.info("[IconAnimator] Started (tick {}ms)", TICK_MS);
    }

    private BufferedImage load(String path) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing: " + path);
            return ImageIO.read(in);
        }
    }

    private void tick() {
        try {
            long now = System.currentTimeMillis();

            // Blink state machine — variable duration, occasional double/triple blinks
            if (blinking && now >= blinkUntil) {
                blinking = false;
                if (pendingDoubleBlinks > 0) {
                    // Schedule the next quick blink very soon (80-150ms gap)
                    pendingDoubleBlinks--;
                    nextBlinkAt = now + 80 + (long)(Math.random() * 70);
                } else {
                    // Random gap until next blink cluster: 1.2s to 7.5s
                    nextBlinkAt = now + 1200 + (long)(Math.random() * 6300);
                }
            } else if (!blinking && now >= nextBlinkAt) {
                blinking = true;
                // Variable blink duration: 90ms (quick) to 260ms (sleepy)
                long duration;
                double r = Math.random();
                if (r < 0.15) duration = 220 + (long)(Math.random() * 100);   // 15% sleepy blink
                else if (r < 0.35) duration = 140 + (long)(Math.random() * 60); // 20% medium
                else duration = 90 + (long)(Math.random() * 50);               // 65% quick
                blinkUntil = now + duration;
                // When starting a fresh blink cluster (not already mid-chain), 20% chance to double,
                // 5% chance to triple-blink (feels more natural / alive)
                if (pendingDoubleBlinks == 0) {
                    double c = Math.random();
                    if (c < 0.05) pendingDoubleBlinks = 2;
                    else if (c < 0.25) pendingDoubleBlinks = 1;
                }
            }

            // Mouse tracking: check cursor position, detect movement
            Point mouse = null;
            try {
                mouse = MouseInfo.getPointerInfo() != null
                        ? MouseInfo.getPointerInfo().getLocation() : null;
            } catch (Exception ignored) {}

            if (mouse != null) {
                if (lastMouse == null || mouse.distance(lastMouse) > 2) {
                    lastMouseMoveAt = now;
                    lastMouse = mouse;
                }
            }

            if (mouse != null && (now - lastMouseMoveAt) < FOLLOW_TIMEOUT_MS) {
                // Assume taskbar icon is near bottom-center of primary screen.
                // Compute pupil direction from that anchor toward the cursor.
                Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
                double eyeX = screen.x + screen.width / 2.0;
                double eyeY = screen.y + screen.height;  // very bottom
                double dx = mouse.x - eyeX;
                double dy = mouse.y - eyeY;
                // Normalize by half-width / full-height; scale for responsiveness
                double nx = Math.max(-1.0, Math.min(1.0, (dx / (screen.width / 2.0)) * 1.2));
                double ny = Math.max(-1.0, Math.min(1.0, (dy / screen.height) * 1.5));
                targetX = nx;
                targetY = ny;
                nextTargetAt = now + 200;
            } else if (now >= nextTargetAt) {
                // Idle — random gaze
                double angle = Math.random() * Math.PI * 2;
                double dist = Math.random();
                targetX = Math.cos(angle) * dist;
                targetY = Math.sin(angle) * dist * 0.7;
                nextTargetAt = now + 500 + (long)(Math.random() * 1800);
            }

            // Ease pupil toward target
            currentX += (targetX - currentX) * 0.22;
            currentY += (targetY - currentY) * 0.22;

            Image i256 = render(base256);
            Image i64  = render(base64);
            Image i32  = render(base32);

            Platform.runLater(() -> {
                if (primaryStage != null) {
                    primaryStage.getIcons().setAll(i256, i64, i32);
                }
            });
        } catch (Exception e) {
            log.debug("[IconAnimator] tick: {}", e.getMessage());
        }
    }

    private Image render(BufferedImage base) {
        int w = base.getWidth();
        int h = base.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(base, 0, 0, null);

        double eyeR   = w * EYE_RADIUS;
        double pupilR = w * PUPIL_RADIUS;
        double range  = w * PUPIL_RANGE;

        drawEye(g, w * LEFT_EYE_X,  h * LEFT_EYE_Y,  eyeR, pupilR, range);
        drawEye(g, w * RIGHT_EYE_X, h * RIGHT_EYE_Y, eyeR, pupilR, range);

        g.dispose();
        return SwingFXUtils.toFXImage(out, null);
    }

    private void drawEye(Graphics2D g, double cx, double cy, double eyeR, double pupilR, double range) {
        // Eye white
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Double(cx - eyeR, cy - eyeR, eyeR * 2, eyeR * 2));

        if (blinking) {
            // Closed — thin dark line
            g.setColor(new Color(25, 28, 40));
            int lh = Math.max(1, (int)Math.round(eyeR * 0.22));
            g.fillRect((int)(cx - eyeR), (int)(cy - lh / 2.0), (int)(eyeR * 2), lh);
        } else {
            double px = cx + currentX * range;
            double py = cy + currentY * range;

            // Pupil
            g.setColor(new Color(25, 28, 40));
            g.fill(new Ellipse2D.Double(px - pupilR, py - pupilR, pupilR * 2, pupilR * 2));

            // Shine
            g.setColor(new Color(255, 255, 255, 220));
            double sr = pupilR * 0.4;
            g.fill(new Ellipse2D.Double(
                    px - pupilR * 0.35 - sr / 2,
                    py - pupilR * 0.35 - sr / 2,
                    sr, sr));
        }
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }
}
