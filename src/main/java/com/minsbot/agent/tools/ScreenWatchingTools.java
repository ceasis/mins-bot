package com.minsbot.agent.tools;

import com.minsbot.agent.AsyncMessageService;
import com.minsbot.agent.GeminiVisionService;
import com.minsbot.agent.ScreenMemoryService;
import com.minsbot.agent.SystemControlService;
import com.minsbot.agent.VisionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.awt.MouseInfo;
import java.awt.Point;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Screen watch mode — continuous background screen observation with AI vision analysis.
 * Supports two modes:
 * <ul>
 *   <li><b>click mode</b> (default): captures after each mouse action (click/draw stroke ends)</li>
 *   <li><b>interval mode</b>: captures at fixed time intervals</li>
 * </ul>
 * Enables interactive features like "guess what I'm drawing" by capturing the screen,
 * analyzing it with Gemini/Vision AI, and pushing observations to the chat.
 */
@Component
public class ScreenWatchingTools {

    private static final Logger log = LoggerFactory.getLogger(ScreenWatchingTools.class);

    private static final int MAX_ROUNDS = 1800;              // 30 minutes at 1s interval

    // Click-mode timing constants
    private static final int POLL_INTERVAL_MS = 100;        // Check mouse position every 100ms
    private static final int SETTLE_TIME_MS = 500;           // Mouse must be still 500ms after moving = action done
    private static final int MIN_CAPTURE_GAP_MS = 1000;      // Minimum 1s between captures (per SPECS)
    private static final int MAX_IDLE_CAPTURE_MS = 1000;     // Also capture every 1s when idle (per SPECS)
    private static final long IDLE_TIMEOUT_MS = 30 * 60 * 1000L; // 30 minutes no action = auto-stop

    private final ToolExecutionNotifier notifier;
    private final SystemControlService systemControl;
    private final GeminiVisionService geminiVisionService;
    private final VisionService visionService;
    private final ScreenMemoryService screenMemoryService;
    private final AsyncMessageService asyncMessages;

    private volatile boolean watching = false;
    private volatile Thread watchThread;
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "watch-analysis");
        t.setDaemon(true);
        return t;
    });
    /** True while an analysis is in-flight — prevents queueing multiple concurrent analyses. */
    private final AtomicBoolean analysisInFlight = new AtomicBoolean(false);

    /** Separate queue for watch observations — rendered in the sticky live panel, NOT as chat messages. */
    private final java.util.concurrent.ConcurrentLinkedQueue<String> watchFeed = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** The most recent watch observation — available for main AI context. */
    private volatile String latestObservation = null;

    /** Recent REACT messages pushed to chat — used for semantic deduplication. */
    private final java.util.List<String> recentReactMessages = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private static final int MAX_RECENT_MESSAGES = 10;
    private static final double SIMILARITY_THRESHOLD = 0.55;

    /** Exposed for the status API endpoint. */
    public boolean isWatching() { return watching; }

    /** Get the latest observation (for injecting into main AI context). */
    public String getLatestObservation() { return latestObservation; }

    /** Drain all pending watch observations (called by the frontend poll endpoint). */
    public java.util.List<String> drainObservations() {
        java.util.List<String> result = new java.util.ArrayList<>();
        String msg;
        while ((msg = watchFeed.poll()) != null) {
            result.add(msg);
        }
        return result;
    }

    public ScreenWatchingTools(ToolExecutionNotifier notifier,
                               SystemControlService systemControl,
                               GeminiVisionService geminiVisionService,
                               VisionService visionService,
                               ScreenMemoryService screenMemoryService,
                               AsyncMessageService asyncMessages) {
        this.notifier = notifier;
        this.systemControl = systemControl;
        this.geminiVisionService = geminiVisionService;
        this.visionService = visionService;
        this.screenMemoryService = screenMemoryService;
        this.asyncMessages = asyncMessages;
    }

    @Tool(description = "Start continuously watching the screen in the background. "
            + "Captures a screenshot every time the user clicks or finishes drawing a stroke, "
            + "analyzes it with AI vision, and pushes observations to the chat. "
            + "Use for guessing games ('guess what I'm drawing'), screen monitoring, or live commentary. "
            + "The watch runs in the background — you can respond to the user immediately. "
            + "Call stopScreenWatch() to stop.")
    public String startScreenWatch(
            @ToolParam(description = "Purpose of watching — guides the AI analysis. "
                    + "Examples: 'guess what the user is drawing', 'describe what the user is doing', "
                    + "'monitor for changes on screen'") String purpose,
            @ToolParam(description = "Mode: 'click' (default) captures after each mouse action, "
                    + "'interval' captures every N seconds. For drawing games always use 'click'.") String mode) {
        notifier.notify("Starting screen watch mode...");

        if (watching) {
            log.info("[WatchMode] Already active — ignoring duplicate start");
            return "Watch mode is already active. Call stopScreenWatch() first to restart.";
        }

        String safePurpose = (purpose == null || purpose.isBlank()) ? "observe the screen" : purpose.trim();
        boolean clickMode = !"interval".equalsIgnoreCase(mode != null ? mode.trim() : "click");

        log.info("[WatchMode] Starting — purpose: '{}', mode: {}, max rounds: {}",
                safePurpose, clickMode ? "CLICK" : "INTERVAL", MAX_ROUNDS);

        watching = true;
        recentReactMessages.clear();
        latestObservation = null;

        watchThread = new Thread(() -> {
            if (clickMode) {
                runClickWatchLoop(safePurpose);
            } else {
                runIntervalWatchLoop(safePurpose, 5);
            }
        }, "screen-watch");
        watchThread.setDaemon(true);
        watchThread.start();

        String modeDesc = clickMode
                ? "Capturing after each mouse click/draw action."
                : "Capturing every 5 seconds.";
        return "Watch mode started (" + (clickMode ? "click" : "interval") + " mode). "
                + modeDesc + " Purpose: " + safePurpose
                + ". Observations will appear in chat. Say 'stop watching' to end.";
    }

    @Tool(description = "Stop the continuous screen watch mode. "
            + "Use when the user says 'stop watching', 'stop guessing', 'stop observing', or similar.")
    public String stopScreenWatch() {
        notifier.notify("Stopping screen watch...");
        if (!watching) {
            return "Watch mode is not active.";
        }
        log.info("[WatchMode] Stop requested");
        watching = false;
        if (watchThread != null) {
            watchThread.interrupt();
        }
        return "Watch mode stopped.";
    }

    // ═══ Click-triggered watch loop ═══

    /**
     * Monitors mouse position at high frequency. When the mouse moves and then
     * settles (stops for SETTLE_TIME_MS), it means the user finished a click or
     * draw stroke — trigger a capture and analysis.
     */
    private void runClickWatchLoop(String purpose) {
        int round = 0;
        long lastCaptureTime = 0;
        Point lastPos = getMousePosition();
        long lastMoveTime = System.currentTimeMillis();
        long lastActionTime = System.currentTimeMillis(); // tracks last user action for idle timeout
        boolean wasMoving = false;

        try {
            log.info("[WatchMode-Click] Loop started. Polling every {}ms, settle: {}ms, min gap: {}ms, idle timeout: {}min",
                    POLL_INTERVAL_MS, SETTLE_TIME_MS, MIN_CAPTURE_GAP_MS, IDLE_TIMEOUT_MS / 60000);

            while (watching && round < MAX_ROUNDS) {
                Thread.sleep(POLL_INTERVAL_MS);

                Point currentPos = getMousePosition();
                long now = System.currentTimeMillis();

                // 30-minute idle timeout — auto-stop if no mouse action
                if (now - lastActionTime >= IDLE_TIMEOUT_MS) {
                    log.info("[WatchMode-Click] Idle timeout ({}min no action) — stopping",
                            IDLE_TIMEOUT_MS / 60000);
                    watchFeed.add("Watch mode ended (no activity for 30 minutes).");
                    break;
                }

                boolean moved = !currentPos.equals(lastPos);

                if (moved) {
                    // Mouse is moving — user is actively doing something
                    lastMoveTime = now;
                    lastActionTime = now;
                    wasMoving = true;
                    lastPos = currentPos;
                    continue;
                }

                // Mouse is still — check if it just settled after movement
                long stillDuration = now - lastMoveTime;
                long sinceLastCapture = now - lastCaptureTime;

                boolean settledAfterAction = wasMoving && stillDuration >= SETTLE_TIME_MS
                        && sinceLastCapture >= MIN_CAPTURE_GAP_MS;
                boolean idleCapture = sinceLastCapture >= MAX_IDLE_CAPTURE_MS && lastCaptureTime > 0;

                if (settledAfterAction || idleCapture) {
                    round++;
                    wasMoving = false;

                    if (settledAfterAction) {
                        log.info("[WatchMode-Click] Mouse settled after action — triggering capture (round {})", round);
                    } else {
                        log.info("[WatchMode-Click] Periodic 1s capture (round {})", round);
                    }

                    lastCaptureTime = now;
                    captureAndPush(purpose, round);
                }

                // First capture: trigger on first settle even if we haven't moved yet
                if (round == 0 && lastCaptureTime == 0) {
                    round++;
                    lastCaptureTime = now;
                    log.info("[WatchMode-Click] Initial capture (round {})", round);
                    captureAndPush(purpose, round);
                }

                lastPos = currentPos;
            }
        } catch (InterruptedException e) {
            log.info("[WatchMode-Click] Interrupted");
        } catch (Exception e) {
            log.warn("[WatchMode-Click] Loop ended with exception: {}", e.getMessage());
        } finally {
            watching = false;
            watchThread = null;
            log.info("[WatchMode-Click] Ended after {} rounds", round);
            if (round >= MAX_ROUNDS) {
                watchFeed.add("Watch mode ended (reached maximum observation limit).");
            }
        }
    }

    // ═══ Interval-based watch loop (fallback) ═══

    private void runIntervalWatchLoop(String purpose, int intervalSeconds) {
        int round = 0;
        try {
            while (watching && round < MAX_ROUNDS) {
                round++;
                log.info("[WatchMode-Interval] Round {}/{}", round, MAX_ROUNDS);
                captureAndPush(purpose, round);
                Thread.sleep(intervalSeconds * 1000L);
            }
        } catch (InterruptedException e) {
            log.info("[WatchMode-Interval] Interrupted");
        } catch (Exception e) {
            log.warn("[WatchMode-Interval] Loop ended with exception: {}", e.getMessage());
        } finally {
            watching = false;
            watchThread = null;
            log.info("[WatchMode-Interval] Ended after {} rounds", round);
            if (round >= MAX_ROUNDS) {
                watchFeed.add("Watch mode ended (reached maximum observation limit).");
            }
        }
    }

    // ═══ Capture + analyze + push ═══

    /**
     * Takes a screenshot synchronously (fast ~100ms), then submits the vision
     * analysis to a background thread so the mouse-tracking loop isn't blocked.
     * Skips if a previous analysis is still in-flight.
     */
    private void captureAndPush(String purpose, int round) {
        if (analysisInFlight.get()) {
            log.debug("[WatchMode] Round {} — skipping, previous analysis still in-flight", round);
            return;
        }

        // Screenshot is fast (~100ms) — do it on the watch thread
        String result = systemControl.takeScreenshot();

        if (result == null || !result.startsWith("Screenshot saved:")) {
            log.warn("[WatchMode] Round {} — screenshot failed: {}", round, result);
            return;
        }

        String pathStr = result.replace("Screenshot saved: ", "").trim();
        Path screenshotPath = Paths.get(pathStr);
        log.info("[WatchMode] Round {} — screenshot: {}", round, screenshotPath.getFileName());

        // Vision analysis is slow (2-5s) — run on separate thread
        analysisInFlight.set(true);
        analysisExecutor.submit(() -> {
            try {
                long t0 = System.currentTimeMillis();
                String observation = analyzeForWatchMode(screenshotPath, purpose, round);
                long dt = System.currentTimeMillis() - t0;

                if (observation != null && !observation.isBlank()) {
                    log.info("[WatchMode] Round {} — observation in {}ms ({} chars): {}",
                            round, dt, observation.length(),
                            observation.length() > 200 ? observation.substring(0, 200) + "..." : observation);
                    routeObservation(observation);
                } else {
                    log.warn("[WatchMode] Round {} — no observation (vision failed) in {}ms", round, dt);
                }
            } catch (Exception e) {
                log.warn("[WatchMode] Round {} — analysis exception: {}", round, e.getMessage());
            } finally {
                analysisInFlight.set(false);
            }
        });
    }

    // ═══ Mouse position helper ═══

    private static Point getMousePosition() {
        try {
            return MouseInfo.getPointerInfo().getLocation();
        } catch (Exception e) {
            return new Point(0, 0);
        }
    }

    // ═══ Route observation to watch feed or chat ═══

    private void routeObservation(String observation) {
        String text = observation.trim();
        String clean;

        if (text.startsWith("[REACT]")) {
            clean = text.substring(7).trim();
            if (!clean.isBlank()) {
                // Semantic dedup: skip if similar to any recent REACT message
                if (isSimilarToRecent(clean)) {
                    log.debug("[WatchMode] REACT semantically similar to recent — skipping chat push");
                } else {
                    log.info("[WatchMode] REACT → chat: {}", clean);
                    asyncMessages.push(clean);
                    addToRecent(clean);
                }
                watchFeed.add(clean);
            }
        } else if (text.startsWith("[OBSERVE]")) {
            clean = text.substring(9).trim();
            if (!clean.isBlank()) {
                watchFeed.add(clean);
            }
        } else {
            clean = text;
            watchFeed.add(text);
        }

        // Always store the latest observation for main AI context
        if (clean != null && !clean.isBlank()) {
            latestObservation = clean;
        }
    }

    // ═══ Semantic deduplication ═══

    /** Check if a message is semantically similar to any recent REACT message. */
    private boolean isSimilarToRecent(String message) {
        Set<String> words = extractKeywords(message);
        if (words.isEmpty()) return false;

        synchronized (recentReactMessages) {
            for (String recent : recentReactMessages) {
                Set<String> recentWords = extractKeywords(recent);
                if (recentWords.isEmpty()) continue;
                double sim = jaccardSimilarity(words, recentWords);
                if (sim >= SIMILARITY_THRESHOLD) {
                    log.debug("[WatchMode] Similarity {} >= {} — duplicate",
                            String.format("%.2f", sim), String.format("%.2f", SIMILARITY_THRESHOLD));
                    return true;
                }
            }
        }
        return false;
    }

    private void addToRecent(String message) {
        synchronized (recentReactMessages) {
            recentReactMessages.add(message);
            while (recentReactMessages.size() > MAX_RECENT_MESSAGES) {
                recentReactMessages.remove(0);
            }
        }
    }

    /** Extract lowercase keywords (3+ chars, no stop words). */
    private static Set<String> extractKeywords(String text) {
        Set<String> words = new java.util.HashSet<>();
        for (String w : text.toLowerCase().split("[^a-z0-9]+")) {
            if (w.length() >= 3 && !STOP_WORDS.contains(w)) {
                words.add(w);
            }
        }
        return words;
    }

    private static double jaccardSimilarity(Set<String> a, Set<String> b) {
        Set<String> intersection = new java.util.HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new java.util.HashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "you", "your", "are", "and", "for", "that", "this", "with",
            "have", "from", "not", "but", "was", "were", "been", "being",
            "can", "could", "would", "should", "will", "shall", "may", "might",
            "its", "it's", "they", "them", "their", "what", "which", "who",
            "how", "when", "where", "here", "there", "some", "more", "like",
            "looks", "look", "also", "just", "still", "seems"
    );

    // ═══ Vision analysis ═══

    private String analyzeForWatchMode(Path screenshotPath, String purpose, int round) {
        String prompt = buildWatchPrompt(purpose, round);
        log.info("[WatchMode] Analysis prompt ({} chars): {}", prompt.length(), prompt.replace('\n', ' '));

        // Try Gemini first (fast 10s timeout for watch mode)
        if (geminiVisionService.isAvailable()) {
            log.info("[WatchMode] Sending to Gemini (quick, 10s timeout)...");
            try {
                long t0 = System.currentTimeMillis();
                String geminiResult = geminiVisionService.analyzeQuick(screenshotPath, prompt);
                long dt = System.currentTimeMillis() - t0;
                if (geminiResult != null && !geminiResult.isBlank()) {
                    log.info("[WatchMode] Gemini SUCCESS in {}ms", dt);
                    return geminiResult;
                }
                log.warn("[WatchMode] Gemini returned empty after {}ms", dt);
            } catch (Exception e) {
                log.warn("[WatchMode] Gemini FAILED: {}", e.getMessage());
            }
        }

        // Fallback: OpenAI Vision with the same watch prompt (10s timeout)
        if (visionService.isAvailable()) {
            log.info("[WatchMode] Falling back to OpenAI Vision (with watch prompt)...");
            try {
                long t0 = System.currentTimeMillis();
                String visionResult = visionService.analyzeWithPrompt(screenshotPath, prompt);
                long dt = System.currentTimeMillis() - t0;
                if (visionResult != null && !visionResult.isBlank()) {
                    log.info("[WatchMode] OpenAI Vision SUCCESS in {}ms", dt);
                    return visionResult;
                }
                log.warn("[WatchMode] OpenAI Vision returned empty after {}ms", dt);
            } catch (Exception e) {
                log.warn("[WatchMode] OpenAI Vision FAILED: {}", e.getMessage());
            }
        }

        // Last resort: OCR (local, instant)
        try {
            List<ScreenMemoryService.OcrWord> ocrWords =
                    screenMemoryService.runOcrWithBounds(screenshotPath);
            if (!ocrWords.isEmpty()) {
                Set<String> seen = new LinkedHashSet<>();
                for (ScreenMemoryService.OcrWord w : ocrWords) {
                    if (!w.text().isBlank() && w.text().length() > 1) seen.add(w.text().trim());
                }
                if (!seen.isEmpty()) {
                    log.info("[WatchMode] OCR fallback: {} text items", seen.size());
                    return "I can see text on screen: " + String.join(", ", seen);
                }
            }
        } catch (Exception e) {
            log.warn("[WatchMode] OCR FAILED: {}", e.getMessage());
        }

        return null;
    }

    private static String buildWatchPrompt(String purpose, int round) {
        return """
                Observation #%d. Purpose: %s
                You are a helpful assistant actively watching the user's screen. Look at this screenshot.

                You MUST participate and help — not just observe. Use [REACT] to respond, [OBSERVE] only \
                when there is truly nothing to contribute.

                USE [REACT] when you see ANY of these:
                - A question on screen (e.g. "what is 2+2?", "what is your name?")
                - A list or template to fill in (e.g. "Top 3 Movies: 1. 2. 3." — fill in the answers!)
                - A prompt asking for suggestions, ideas, or recommendations
                - Incomplete content that needs your input (blank fields, empty bullets, "___")
                - A request or task (e.g. "translate this", "help me with...", "fix this code")
                - A quiz, trivia, or game where the user expects answers
                - Text that clearly invites your participation or opinion

                USE [OBSERVE] ONLY when:
                - The user is passively browsing, scrolling, or navigating (no input needed)
                - The screen shows content that doesn't ask for or need your contribution
                - Drawing/creating something where describing what you see is the purpose

                Examples:
                - "Top 3 Sci Fi Movies? 1. 2. 3." → [REACT] 1. Blade Runner 2049  2. Interstellar  3. Dune
                - "Best programming languages: 1.__ 2.__ 3.__" → [REACT] 1. Python  2. Java  3. Rust
                - "what is 2+2?" → [REACT] 2 + 2 = 4
                - "translate hello to spanish" → [REACT] "Hello" in Spanish is "Hola"
                - "Name 5 fruits:" → [REACT] Apple, Banana, Mango, Strawberry, Orange
                - User is browsing YouTube → [OBSERVE] User is watching a YouTube video.
                - User is drawing in Paint → [OBSERVE] User is drawing a red circle in Paint.

                STRICT: Always start with [REACT] or [OBSERVE]. Keep it short (1-3 sentences max). \
                When in doubt, REACT — be helpful and participate!"""
                .formatted(round, purpose);
    }
}
