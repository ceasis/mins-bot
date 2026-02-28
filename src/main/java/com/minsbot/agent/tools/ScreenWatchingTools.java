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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Screen watch mode — continuous background screen observation with AI vision analysis.
 * Enables interactive features like "guess what I'm drawing" by periodically capturing
 * the screen, analyzing it, and pushing observations to the chat.
 */
@Component
public class ScreenWatchingTools {

    private static final Logger log = LoggerFactory.getLogger(ScreenWatchingTools.class);

    private static final int MIN_INTERVAL = 3;
    private static final int MAX_INTERVAL = 30;
    private static final int DEFAULT_INTERVAL = 5;
    private static final int MAX_ROUNDS = 60;

    private final ToolExecutionNotifier notifier;
    private final SystemControlService systemControl;
    private final GeminiVisionService geminiVisionService;
    private final VisionService visionService;
    private final AsyncMessageService asyncMessages;
    private final ScreenMemoryService screenMemoryService;

    private volatile boolean watching = false;
    private volatile Thread watchThread;

    public ScreenWatchingTools(ToolExecutionNotifier notifier,
                               SystemControlService systemControl,
                               GeminiVisionService geminiVisionService,
                               VisionService visionService,
                               AsyncMessageService asyncMessages,
                               ScreenMemoryService screenMemoryService) {
        this.notifier = notifier;
        this.systemControl = systemControl;
        this.geminiVisionService = geminiVisionService;
        this.visionService = visionService;
        this.asyncMessages = asyncMessages;
        this.screenMemoryService = screenMemoryService;
    }

    @Tool(description = "Start continuously watching the screen in the background. "
            + "Takes periodic screenshots, analyzes them with AI vision, and pushes observations to the chat. "
            + "Use for guessing games ('guess what I'm drawing'), screen monitoring, or live commentary. "
            + "The watch runs in the background — you can respond to the user immediately. "
            + "Call stopScreenWatch() to stop.")
    public String startScreenWatch(
            @ToolParam(description = "Purpose of watching — guides the AI analysis. "
                    + "Examples: 'guess what the user is drawing', 'describe what the user is doing', "
                    + "'monitor for changes on screen'") String purpose,
            @ToolParam(description = "Seconds between each observation (min 3, max 30, default 5)") double intervalSeconds) {
        notifier.notify("Starting screen watch mode...");

        if (watching) {
            log.info("[WatchMode] Already active — ignoring duplicate start");
            return "Watch mode is already active. Call stopScreenWatch() first to restart.";
        }

        int interval = (int) Math.round(intervalSeconds);
        if (interval < MIN_INTERVAL) interval = MIN_INTERVAL;
        if (interval > MAX_INTERVAL) interval = MAX_INTERVAL;

        String safePurpose = (purpose == null || purpose.isBlank()) ? "observe the screen" : purpose.trim();

        log.info("[WatchMode] Starting — purpose: '{}', interval: {}s, max rounds: {}",
                safePurpose, interval, MAX_ROUNDS);

        watching = true;
        final int finalInterval = interval;

        watchThread = new Thread(() -> runWatchLoop(safePurpose, finalInterval), "screen-watch");
        watchThread.setDaemon(true);
        watchThread.start();

        return "Watch mode started. Observing every " + interval + " seconds. Purpose: " + safePurpose
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

    // ═══ Background observe loop ═══

    private void runWatchLoop(String purpose, int intervalSeconds) {
        int round = 0;
        try {
            while (watching && round < MAX_ROUNDS) {
                round++;
                log.info("[WatchMode] Round {}/{} — capturing screen...", round, MAX_ROUNDS);
                long roundStart = System.currentTimeMillis();

                // Hide bot window for clean capture
                try { com.minsbot.FloatingAppLauncher.hideWindow(); } catch (Exception ignored) {}
                try { Thread.sleep(150); } catch (InterruptedException e) { break; }

                String result = systemControl.takeScreenshot();

                // Restore window
                try { com.minsbot.FloatingAppLauncher.showWindow(); } catch (Exception ignored) {}

                if (result == null || !result.startsWith("Screenshot saved:")) {
                    log.warn("[WatchMode] Round {} — screenshot failed: {}", round, result);
                    sleepOrBreak(intervalSeconds);
                    continue;
                }

                String pathStr = result.replace("Screenshot saved: ", "").trim();
                Path screenshotPath = Paths.get(pathStr);
                log.info("[WatchMode] Round {} — screenshot: {}", round, screenshotPath.getFileName());

                // Analyze with vision AI
                String observation = analyzeForWatchMode(screenshotPath, purpose, round);

                long roundMs = System.currentTimeMillis() - roundStart;

                if (observation != null && !observation.isBlank()) {
                    log.info("[WatchMode] Round {} — observation in {}ms ({} chars): {}",
                            round, roundMs, observation.length(),
                            observation.length() > 200 ? observation.substring(0, 200) + "..." : observation);
                    asyncMessages.push(observation);
                } else {
                    log.warn("[WatchMode] Round {} — no observation (vision failed) in {}ms", round, roundMs);
                }

                sleepOrBreak(intervalSeconds);
            }
        } catch (Exception e) {
            log.warn("[WatchMode] Loop ended with exception: {}", e.getMessage());
        } finally {
            watching = false;
            watchThread = null;
            log.info("[WatchMode] Ended after {} rounds", round);
            if (round >= MAX_ROUNDS) {
                asyncMessages.push("Watch mode ended (reached maximum observation limit).");
            }
        }
    }

    private void sleepOrBreak(int intervalSeconds) {
        try {
            Thread.sleep(intervalSeconds * 1000L);
        } catch (InterruptedException e) {
            watching = false;
        }
    }

    // ═══ Vision analysis ═══

    private String analyzeForWatchMode(Path screenshotPath, String purpose, int round) {
        String prompt = buildWatchPrompt(purpose, round);
        log.info("[WatchMode] Analysis prompt ({} chars):\n{}", prompt.length(), prompt);

        // Try Gemini first
        if (geminiVisionService.isAvailable()) {
            log.info("[WatchMode] Sending to Gemini for analysis...");
            try {
                long t0 = System.currentTimeMillis();
                String geminiResult = geminiVisionService.analyze(screenshotPath, prompt);
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

        // Fallback: OpenAI Vision
        if (visionService.isAvailable()) {
            log.info("[WatchMode] Falling back to OpenAI Vision...");
            try {
                long t0 = System.currentTimeMillis();
                String visionResult = visionService.analyzeScreenshot(screenshotPath);
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

        // Last resort: OCR
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
                You are observing the user's screen in real-time (observation #%d). Your purpose: %s

                Look at this screenshot and provide a SHORT observation (1-2 sentences max).
                - If the purpose mentions "guess" or "drawing": describe what shape or object you see \
                being drawn and make your best guess. Be specific about shapes (circle, square, house, \
                tree, face, etc.), colors, and progress. If the drawing is incomplete, say what it \
                looks like so far.
                - If the purpose mentions "watch" or "monitor": briefly describe what changed or \
                what the user is currently doing.
                - Be conversational and fun. React naturally to what you see.
                - If nothing has changed since the last observation, say so briefly.

                IMPORTANT: Keep responses SHORT (1-2 sentences). This is a live feed, not a report."""
                .formatted(round, purpose);
    }
}
