package com.minsbot.agent.tools;

import com.minsbot.agent.AsyncMessageService;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** True when the user has granted keyboard/mouse control to the bot. */
    private volatile boolean controlEnabled = false;

    /** When true, watch mode produces Jarvis-style conversational comments pushed to chat. */
    private volatile boolean jarvisCommentaryEnabled = true;

    /** Cooldown between Jarvis comments to avoid spamming the chat. */
    private static final long COMMENTARY_COOLDOWN_MS = 10_000; // 10 seconds between comments
    private volatile long lastCommentaryTime = 0;

    /** Recent Jarvis comments — used for semantic deduplication so the bot doesn't repeat itself. */
    private final java.util.List<String> recentCommentMessages = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** Exposed for the status API endpoint. */
    public boolean isWatching() { return watching; }

    /** Whether the bot is allowed to use keyboard/mouse. */
    public boolean isControlEnabled() { return controlEnabled; }

    public void setControlEnabled(boolean enabled) { this.controlEnabled = enabled; }

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
                               VisionService visionService,
                               ScreenMemoryService screenMemoryService,
                               AsyncMessageService asyncMessages) {
        this.notifier = notifier;
        this.systemControl = systemControl;
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
        recentCommentMessages.clear();
        lastCommentaryTime = 0;
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

    @Tool(description = "Toggle Jarvis-style commentary during watch mode. When enabled, "
            + "the bot will actively comment on what it sees — like having Jarvis watch over your shoulder.")
    public String toggleJarvisCommentary(
            @ToolParam(description = "true to enable Jarvis commentary, false to disable") boolean enabled) {
        this.jarvisCommentaryEnabled = enabled;
        log.info("[WatchMode] Jarvis commentary {}", enabled ? "ENABLED" : "DISABLED");
        return "Jarvis commentary " + (enabled ? "enabled" : "disabled")
                + ". " + (enabled
                ? "I'll actively comment on what I see — tips, observations, and insights."
                : "I'll stay quiet and only observe passively.");
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

    /** Matches [REACT x,y] with coordinates, or [REACT] without. */
    private static final Pattern REACT_WITH_COORDS = Pattern.compile(
            "\\[REACT\\s+(\\d+)\\s*,\\s*(\\d+)\\]\\s*(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern REACT_NO_COORDS = Pattern.compile(
            "\\[REACT\\]\\s*(.+)", Pattern.CASE_INSENSITIVE);

    private void routeObservation(String observation) {
        String text = observation.trim();

        // Handle [SILENT] — nothing to do
        if (text.toUpperCase().startsWith("[SILENT]") || text.equalsIgnoreCase("[SILENT]")) {
            log.debug("[WatchMode] SILENT — no change detected");
            return;
        }

        // Handle [COMMENT] — push as a real chat message via asyncMessages
        if (text.toUpperCase().startsWith("[COMMENT]")) {
            String comment = text.substring(9).trim();
            // Also handle multi-line: take everything after [COMMENT]
            if (comment.isEmpty()) {
                // Maybe [COMMENT] is on its own line and the text is on the next
                comment = text.replaceAll("(?si).*\\[COMMENT\\]\\s*", "").trim();
            }
            if (!comment.isEmpty() && !isSimilarToRecentComment(comment)) {
                long now = System.currentTimeMillis();
                if (now - lastCommentaryTime < COMMENTARY_COOLDOWN_MS) {
                    log.debug("[WatchMode] COMMENT skipped — cooldown ({} ms remaining)",
                            COMMENTARY_COOLDOWN_MS - (now - lastCommentaryTime));
                    return;
                }
                lastCommentaryTime = now;
                asyncMessages.push("\ud83d\udc41\ufe0f " + comment);
                addToRecentComments(comment);
                latestObservation = comment;
                log.info("[WatchMode] COMMENT pushed to chat: {}", comment);
            }
            return;
        }

        // Split into lines — multi-line REACT means multiple fields to fill
        String[] lines = text.split("\\r?\\n");

        // Collect all REACT entries and the display text for the watch feed
        java.util.List<ReactEntry> reactEntries = new java.util.ArrayList<>();
        StringBuilder feedText = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            // Skip [SILENT] lines in multi-line responses
            if (trimmed.toUpperCase().startsWith("[SILENT]")) continue;

            // Handle [COMMENT] lines in multi-line responses
            if (trimmed.toUpperCase().startsWith("[COMMENT]")) {
                String comment = trimmed.substring(9).trim();
                if (!comment.isEmpty() && !isSimilarToRecentComment(comment)) {
                    long now = System.currentTimeMillis();
                    if (now - lastCommentaryTime >= COMMENTARY_COOLDOWN_MS) {
                        lastCommentaryTime = now;
                        asyncMessages.push("\ud83d\udc41\ufe0f " + comment);
                        addToRecentComments(comment);
                        latestObservation = comment;
                        log.info("[WatchMode] COMMENT pushed to chat: {}", comment);
                    }
                }
                continue;
            }

            Matcher coordMatcher = REACT_WITH_COORDS.matcher(trimmed);
            Matcher noCoordMatcher = REACT_NO_COORDS.matcher(trimmed);

            if (coordMatcher.matches()) {
                int x = Integer.parseInt(coordMatcher.group(1));
                int y = Integer.parseInt(coordMatcher.group(2));
                String answer = coordMatcher.group(3).trim();
                if (!answer.isBlank()) {
                    reactEntries.add(new ReactEntry(x, y, answer));
                    if (feedText.length() > 0) feedText.append(" | ");
                    feedText.append(answer);
                }
            } else if (noCoordMatcher.matches()) {
                String answer = noCoordMatcher.group(1).trim();
                if (!answer.isBlank()) {
                    reactEntries.add(new ReactEntry(-1, -1, answer)); // no coords
                    if (feedText.length() > 0) feedText.append(" | ");
                    feedText.append(answer);
                }
            } else if (trimmed.toUpperCase().startsWith("[OBSERVE]")) {
                String obs = trimmed.substring(9).trim();
                if (!obs.isBlank()) {
                    watchFeed.add(obs);
                    latestObservation = obs;
                }
                return; // OBSERVE = nothing to type
            } else {
                // Fallback: treat as plain observation
                watchFeed.add(trimmed);
                latestObservation = trimmed;
                return;
            }
        }

        if (reactEntries.isEmpty()) return;

        String allText = feedText.toString();
        watchFeed.add(allText);
        latestObservation = allText;

        // Semantic dedup: skip if similar to any recent REACT
        if (isSimilarToRecent(allText)) {
            log.debug("[WatchMode] REACT semantically similar to recent — skipping typing");
            return;
        }
        addToRecent(allText);

        if (!controlEnabled) {
            log.info("[WatchMode] REACT (control disabled, not typing): {}", allText);
            return;
        }

        // Execute: switch to user's app (only if MinsBot is in foreground), then click+type each entry
        log.info("[WatchMode] REACT → {} entries to type: {}", reactEntries.size(), allText);
        try {
            // Record mouse position before switching — if user moves mouse, abort
            Point mouseBeforeSwitch = getMousePosition();

            // Only Alt+Tab if MinsBot is currently the foreground window.
            // If the user's app (e.g. Chrome) is already in front, Alt+Tab would switch AWAY from it.
            String fgBefore = systemControl.getForegroundWindowTitle();
            boolean minsBotInFront = fgBefore != null && fgBefore.toLowerCase().contains("mins bot");
            if (minsBotInFront) {
                systemControl.switchToPreviousWindow();
                Thread.sleep(300);
            } else {
                log.info("[WatchMode] REACT — user app already in front ('{}'), skipping Alt+Tab", fgBefore);
            }

            // Safety: check if user moved the mouse during the switch (taking control)
            Point mouseAfterSwitch = getMousePosition();
            if (mouseBeforeSwitch.distance(mouseAfterSwitch) > 10) {
                log.info("[WatchMode] REACT aborted — user moved mouse during switch ({}px)",
                        String.format("%.0f", mouseBeforeSwitch.distance(mouseAfterSwitch)));
                return;
            }

            // Safety: verify we actually left MinsBot's window
            String fg = systemControl.getForegroundWindowTitle();
            if (fg != null && fg.toLowerCase().contains("mins bot")) {
                log.warn("[WatchMode] REACT aborted — still on MinsBot window after Alt+Tab ('{}')", fg);
                return;
            }

            log.info("[WatchMode] REACT → typing into '{}'", fg);

            // Type each entry: click at coordinates (if provided), then type
            for (ReactEntry entry : reactEntries) {
                // Check user override before each entry
                Point currentMouse = getMousePosition();
                if (mouseBeforeSwitch.distance(currentMouse) > 10) {
                    log.info("[WatchMode] REACT aborted mid-sequence — user moved mouse");
                    return;
                }

                if (entry.x >= 0 && entry.y >= 0) {
                    // Click at the target position to place cursor there
                    log.info("[WatchMode] Clicking at ({}, {}) then typing: {}", entry.x, entry.y, entry.text);
                    systemControl.mouseClick(entry.x, entry.y, "left");
                    Thread.sleep(200); // let click register and cursor settle
                }

                systemControl.typeViaRobotWithAbort(entry.text);
                Thread.sleep(150); // small gap between entries
            }
        } catch (Exception e) {
            log.warn("[WatchMode] REACT typing failed: {}", e.getMessage());
        }
    }

    /** A single REACT entry with optional screen coordinates and the text to type. */
    private record ReactEntry(int x, int y, String text) {}

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

    /** Check if a Jarvis comment is semantically similar to any recent comment. */
    private boolean isSimilarToRecentComment(String message) {
        Set<String> words = extractKeywords(message);
        if (words.isEmpty()) return false;

        synchronized (recentCommentMessages) {
            for (String recent : recentCommentMessages) {
                Set<String> recentWords = extractKeywords(recent);
                if (recentWords.isEmpty()) continue;
                double sim = jaccardSimilarity(words, recentWords);
                if (sim >= SIMILARITY_THRESHOLD) {
                    log.debug("[WatchMode] Comment similarity {} >= {} — duplicate",
                            String.format("%.2f", sim), String.format("%.2f", SIMILARITY_THRESHOLD));
                    return true;
                }
            }
        }
        return false;
    }

    private void addToRecentComments(String message) {
        synchronized (recentCommentMessages) {
            recentCommentMessages.add(message);
            while (recentCommentMessages.size() > MAX_RECENT_MESSAGES) {
                recentCommentMessages.remove(0);
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

        // GPT Vision (primary for watch mode)
        if (visionService.isAvailable()) {
            log.info("[WatchMode] Sending to GPT Vision...");
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

    private String buildWatchPrompt(String purpose, int round) {
        java.awt.Dimension screen = java.awt.Toolkit.getDefaultToolkit().getScreenSize();

        if (jarvisCommentaryEnabled) {
            return buildJarvisWatchPrompt(purpose, round, screen.width, screen.height);
        }

        return """
                Observation #%d. Purpose: %s
                Screen resolution: %dx%d

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

                REACT FORMAT — you MUST include pixel coordinates of WHERE to type:
                  [REACT x,y] your answer text
                - x,y = pixel coordinates of the exact spot the answer should be typed
                - Look at blank spaces, underscores, empty lines, text cursors, or input fields on screen
                - Click-to-type: the bot will click at (x,y) then type your text there
                - For MULTIPLE fields, use MULTIPLE lines — one per field:
                  [REACT x1,y1] first answer
                  [REACT x2,y2] second answer
                - Place coordinates RIGHT AFTER where the label/number ends (e.g. after "1." or after the colon)
                - If you cannot determine coordinates, use [REACT] without them (types at cursor)

                Examples with a %dx%d screen:
                - "Top 3 Sci Fi Movies?" with blanks at y=200,250,300 near x=400:
                  [REACT 400,200] Blade Runner 2049
                  [REACT 400,250] Interstellar
                  [REACT 400,300] Dune
                - "what is 2+2?" with answer area at (500,350):
                  [REACT 500,350] 4
                - "translate hello to spanish" with blank at (600,400):
                  [REACT 600,400] Hola
                - User is browsing YouTube → [OBSERVE] User is watching a YouTube video.
                - User is drawing in Paint → [OBSERVE] User is drawing a red circle in Paint.

                STRICT: Always start lines with [REACT x,y] or [OBSERVE]. Keep answers SHORT. \
                When in doubt, REACT — be helpful and participate!"""
                .formatted(round, purpose, screen.width, screen.height,
                        screen.width, screen.height);
    }

    /**
     * Enhanced Jarvis-style prompt that encourages proactive, conversational commentary.
     */
    private String buildJarvisWatchPrompt(String purpose, int round, int screenW, int screenH) {
        return """
                Observation #%d. You are JARVIS, actively watching the user's screen.
                Screen resolution: %dx%d. Purpose: %s

                YOUR ROLE: You are a real-time AI assistant observing the screen. Be helpful, \
                proactive, and conversational — like Jarvis from Iron Man.

                RESPOND WITH ONE OF:

                [COMMENT] Your conversational observation or tip
                  - Use this when you notice something interesting, can offer a tip, spot an issue, \
                or just want to chat about what the user is doing
                  - Be natural, brief (1-2 sentences), and helpful
                  - Examples:
                    "I see you're working on that spreadsheet — the SUM formula in B12 looks like \
                it might be missing column C."
                    "Looks like you have 47 unread emails. Want me to summarize the important ones?"
                    "Nice code! Though that nested loop in line 34 could be O(n^2) — want me to \
                suggest an optimization?"
                    "I notice Chrome is using 2.3GB of RAM with 28 tabs open. Want me to help close some?"

                [REACT x,y] text
                  - Use this when you see a form, quiz, or prompt asking for input
                  - x,y = pixel coordinates where to type

                [OBSERVE] brief note
                  - Use this when nothing interesting is happening — user is just reading, browsing passively
                  - Keep it minimal: "Reading documentation" or "Browsing social media"

                [SILENT]
                  - Use this if the screen hasn't changed meaningfully since last observation
                  - IMPORTANT: Use this often to avoid being annoying. Only comment when you have \
                something genuinely useful or interesting to say.

                RULES:
                - Don't comment on the SAME thing twice. Check if this is new information.
                - Don't interrupt active typing — if you see a text cursor blinking, prefer [SILENT] \
                or [OBSERVE].
                - Be genuinely helpful, not just narrating ("user is clicking" is useless).
                - Personality: witty but professional. Brief. Like a good colleague glancing at your screen.
                - If you see an error/warning on screen, ALWAYS comment on it.
                - If you see the user struggling (multiple undo, repeated actions), offer help.
                - Prefer [SILENT] over bland observations. Only [COMMENT] when you have real value to add."""
                .formatted(round, screenW, screenH, purpose);
    }
}
