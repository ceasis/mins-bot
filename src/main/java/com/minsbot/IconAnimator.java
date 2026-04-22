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
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
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

    // Mouth position (centered, below eyes)
    private static final double MOUTH_CX         = 0.50;
    private static final double MOUTH_CY         = 0.68;
    private static final double MOUTH_WIDTH      = 0.22;
    private static final double MOUTH_HEIGHT_MAX = 0.09;
    private static final double MOUTH_HEIGHT_MIN = 0.015;  // closed-line thickness

    // Ears — small nubs on the sides
    private static final double EAR_LEFT_CX   = 0.10;
    private static final double EAR_RIGHT_CX  = 0.90;
    private static final double EAR_CY        = 0.48;
    private static final double EAR_WIDTH     = 0.06;
    private static final double EAR_HEIGHT    = 0.12;

    private static final int TICK_MS = 80;  // a touch faster for smoother mouth/ear motion

    // Bored = no bot activity for this long
    private static final long BORED_THRESHOLD_MS = 45_000;
    // Mouth "talking" duration when bot posts a message
    private static final long TALK_MIN_MS = 1200;
    private static final long TALK_PER_CHAR_MS = 35;
    private static final long TALK_MAX_MS = 5000;

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

    // Mouth state
    private long talkingUntil = 0;          // while > now, mouth animates open/close
    private long mouthClosedUntil = 0;      // occasional "mouth neutral-closed" moments
    private long nextMouthCloseAt = 0;

    // Boredom / ear wiggle state
    private long lastBotActivityAt = System.currentTimeMillis();
    private double earWigglePhase = 0;       // 0..2π sine phase

    // Random giggle state — tilts + squint + open grin on a random interval
    private static final long GIGGLE_DURATION_MS = 700;
    private static final long GIGGLE_MIN_GAP_MS = 45_000;
    private static final long GIGGLE_MAX_GAP_MS = 180_000;
    private long giggleStartedAt = 0;
    private long giggleUntil = 0;
    private long nextGiggleAt = System.currentTimeMillis()
            + GIGGLE_MIN_GAP_MS + (long)(Math.random() * (GIGGLE_MAX_GAP_MS - GIGGLE_MIN_GAP_MS));

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
        nextMouthCloseAt = now + 4000 + (long)(Math.random() * 8000);
        lastBotActivityAt = now;

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

            // Random "mouth closed" moments — only when NOT currently talking
            if (talkingUntil <= now && mouthClosedUntil <= now && now >= nextMouthCloseAt) {
                mouthClosedUntil = now + 400 + (long)(Math.random() * 1100);
                nextMouthCloseAt = now + 4000 + (long)(Math.random() * 10000);
            }

            // Advance ear wiggle phase (only renders while bored)
            earWigglePhase += 0.35;
            if (earWigglePhase > Math.PI * 2) earWigglePhase -= Math.PI * 2;

            // Random giggle trigger — fires on its own random schedule
            if (giggleUntil <= now && now >= nextGiggleAt) {
                giggleStartedAt = now;
                giggleUntil = now + GIGGLE_DURATION_MS;
                nextGiggleAt = now + GIGGLE_DURATION_MS + GIGGLE_MIN_GAP_MS
                        + (long)(Math.random() * (GIGGLE_MAX_GAP_MS - GIGGLE_MIN_GAP_MS));
            }

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
        long now = System.currentTimeMillis();
        boolean bored = (now - lastBotActivityAt) > BORED_THRESHOLD_MS;
        boolean giggling = giggleUntil > now;

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        // While giggling: rotate the whole canvas around its center with a
        // fast sine wobble (~7 Hz) so the icon visibly shakes in the taskbar.
        AffineTransform savedXf = null;
        if (giggling) {
            double t = (now - giggleStartedAt) / (double) GIGGLE_DURATION_MS; // 0..1
            double envelope = Math.sin(Math.min(1.0, t) * Math.PI);           // ease in/out
            double wobble = Math.sin((now - giggleStartedAt) * (2 * Math.PI * 7 / 1000.0));
            double angle = Math.toRadians(6.0) * envelope * wobble;           // ±6°
            savedXf = g.getTransform();
            g.rotate(angle, w / 2.0, h / 2.0);
        }

        g.drawImage(base, 0, 0, null);

        // Ears first (behind/beside the head) — wiggle when bored
        drawEars(g, w, h, bored);

        double eyeR   = w * EYE_RADIUS;
        double pupilR = w * PUPIL_RADIUS;
        double range  = w * PUPIL_RANGE;

        if (giggling) {
            // Squinted happy eyes: ^ ^
            drawSquintEye(g, w * LEFT_EYE_X,  h * LEFT_EYE_Y,  eyeR);
            drawSquintEye(g, w * RIGHT_EYE_X, h * RIGHT_EYE_Y, eyeR);
        } else {
            drawEye(g, w * LEFT_EYE_X,  h * LEFT_EYE_Y,  eyeR, pupilR, range);
            drawEye(g, w * RIGHT_EYE_X, h * RIGHT_EYE_Y, eyeR, pupilR, range);
        }

        if (giggling) drawGiggleMouth(g, w, h);
        else          drawMouth(g, w, h, now);

        if (savedXf != null) g.setTransform(savedXf);

        g.dispose();
        return SwingFXUtils.toFXImage(out, null);
    }

    /** Closed arched eye (^-shape) — classic "laughing" face. */
    private void drawSquintEye(Graphics2D g, double cx, double cy, double eyeR) {
        g.setColor(new Color(25, 28, 40));
        float stroke = Math.max(1.5f, (float)(eyeR * 0.28));
        g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double w = eyeR * 2.0;
        double h = eyeR * 1.2;
        // Arc from 20° to 160° — opens downward like "^"
        g.draw(new Arc2D.Double(cx - w / 2, cy - h / 2, w, h, 20, 140, Arc2D.OPEN));
    }

    /** Big open grin — oval filled dark, for giggle frames. */
    private void drawGiggleMouth(Graphics2D g, int w, int h) {
        double cx = w * MOUTH_CX;
        double cy = h * (MOUTH_CY + 0.02);
        double mw = w * (MOUTH_WIDTH * 1.1);
        double mh = h * (MOUTH_HEIGHT_MAX * 1.35);
        Shape oval = new Ellipse2D.Double(cx - mw / 2, cy - mh / 2, mw, mh);
        g.setColor(new Color(140, 30, 50));
        g.fill(oval);
        g.setColor(new Color(25, 28, 40));
        g.setStroke(new BasicStroke(Math.max(1f, (float)(w * 0.008))));
        g.draw(oval);
    }

    /**
     * Draw the mouth. Three states:
     *  - talking (cycles between open oval and closed line every ~140ms, while talkingUntil > now)
     *  - closed  (thin dark line, during random closed moments OR bored)
     *  - smile   (default resting — gentle upward arc)
     */
    private void drawMouth(Graphics2D g, int w, int h, long now) {
        double cx = w * MOUTH_CX;
        double cy = h * MOUTH_CY;
        double mw = w * MOUTH_WIDTH;
        double mhMax = h * MOUTH_HEIGHT_MAX;
        double mhMin = h * MOUTH_HEIGHT_MIN;

        Color lineColor = new Color(25, 28, 40);
        Color mouthFill = new Color(140, 30, 50);  // dark inner
        g.setColor(lineColor);

        if (talkingUntil > now) {
            // Talking — oscillate mouth height using a fast sine so it reads as speech
            double t = (now % 320) / 320.0;
            double openness = (Math.sin(t * Math.PI * 2) + 1) / 2.0; // 0..1
            // Add jitter so it doesn't look metronomic
            openness = Math.max(0.15, openness);
            double mh = mhMin + openness * (mhMax - mhMin);
            double narrowing = 0.75 + 0.25 * (1 - openness); // mouth narrows when closing
            Shape oval = new Ellipse2D.Double(cx - (mw * narrowing) / 2, cy - mh / 2, mw * narrowing, mh);
            g.setColor(mouthFill);
            g.fill(oval);
            g.setColor(lineColor);
            g.setStroke(new BasicStroke(Math.max(1f, (float)(w * 0.006))));
            g.draw(oval);
            return;
        }

        boolean closed = mouthClosedUntil > now;
        if (closed) {
            // Flat dark line — expressionless/neutral-closed
            double lh = Math.max(1.2, mhMin);
            g.fill(new RoundRectangle2D.Double(cx - mw / 2, cy - lh / 2, mw, lh, lh, lh));
        } else {
            // Default: gentle smile arc
            g.setStroke(new BasicStroke(Math.max(1.2f, (float)(w * 0.012)),
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            double smileH = mhMax * 0.55;
            Arc2D smile = new Arc2D.Double(cx - mw / 2, cy - smileH, mw, smileH * 2, 200, 140, Arc2D.OPEN);
            g.draw(smile);
        }
    }

    /**
     * Draw small rounded ear-nubs on the left and right of the head.
     * When bored, they wiggle up/down using the current earWigglePhase.
     */
    private void drawEars(Graphics2D g, int w, int h, boolean bored) {
        double leftX  = w * EAR_LEFT_CX;
        double rightX = w * EAR_RIGHT_CX;
        double cy     = h * EAR_CY;
        double ew     = w * EAR_WIDTH;
        double eh     = h * EAR_HEIGHT;

        // Wiggle offsets — only when bored. Left and right wiggle out of phase for more life.
        double leftTilt  = bored ? Math.sin(earWigglePhase)          * 0.35 : 0; // radians
        double rightTilt = bored ? Math.sin(earWigglePhase + Math.PI) * 0.35 : 0;

        drawEar(g, leftX, cy, ew, eh, leftTilt, true);
        drawEar(g, rightX, cy, ew, eh, rightTilt, false);
    }

    private void drawEar(Graphics2D g, double cx, double cy, double ew, double eh,
                         double tiltRadians, boolean isLeft) {
        AffineTransform prev = g.getTransform();
        // Rotate around an anchor near the head (inner side of the ear) so the tip swings
        double anchorX = cx + (isLeft ? ew * 0.5 : -ew * 0.5);
        g.rotate(tiltRadians, anchorX, cy);

        // Outer ear — softer rounded rect
        g.setColor(new Color(70, 95, 150));
        Shape body = new RoundRectangle2D.Double(cx - ew / 2, cy - eh / 2, ew, eh, ew, ew);
        g.fill(body);

        // Outline
        g.setColor(new Color(25, 28, 40, 180));
        g.setStroke(new BasicStroke(Math.max(1f, (float)(ew * 0.18))));
        g.draw(body);

        // Inner highlight
        g.setColor(new Color(150, 180, 230, 180));
        double innerW = ew * 0.45;
        double innerH = eh * 0.55;
        g.fill(new RoundRectangle2D.Double(cx - innerW / 2, cy - innerH / 2, innerW, innerH, innerW, innerW));

        g.setTransform(prev);
    }

    /**
     * Public hook — call this whenever the bot posts a new chat message.
     * Triggers the "mouth talking" animation proportional to the message length,
     * and resets the boredom timer (ears stop wiggling).
     */
    public void onBotMessage(String text) {
        long now = System.currentTimeMillis();
        int len = text == null ? 0 : text.length();
        long duration = Math.max(TALK_MIN_MS, Math.min(TALK_MAX_MS, (long)(len * TALK_PER_CHAR_MS)));
        talkingUntil = Math.max(talkingUntil, now + duration);
        lastBotActivityAt = now;
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
