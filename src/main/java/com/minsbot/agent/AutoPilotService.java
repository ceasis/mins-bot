package com.minsbot.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Auto-pilot mode: watches the screen and proactively offers contextual help.
 * Captures screenshots at intervals, analyzes them with Gemini vision,
 * detects what the user is doing, and pushes suggestions to the chat.
 * <p>
 * Examples:
 * <ul>
 *   <li>"I see you're writing an email — want me to check grammar?"</li>
 *   <li>"Looks like you have a compile error on line 42 — want me to help fix it?"</li>
 *   <li>"You have 3 unread Slack messages — want a summary?"</li>
 *   <li>"I notice you're on a shopping site — want me to find a better price?"</li>
 * </ul>
 */
@Service
public class AutoPilotService {

    private static final Logger log = LoggerFactory.getLogger(AutoPilotService.class);

    private final SystemControlService systemControl;
    private final GeminiVisionService geminiVision;
    private final ScreenMemoryService screenMemory;

    /** Suggestions waiting to be sent to the user via the async message feed. */
    private final ConcurrentLinkedQueue<String> suggestions = new ConcurrentLinkedQueue<>();

    private volatile boolean enabled = false;
    private volatile Thread pilotThread;
    private volatile String lastContext = "";
    private volatile long lastSuggestionTime = 0;

    /** How often to analyze the screen (ms). */
    private volatile int intervalMs = 15_000;
    /** Minimum gap between suggestions to avoid spamming (ms). */
    private volatile long cooldownMs = 45_000;
    /** User activity tracking: if mouse hasn't moved, user may be AFK — skip suggestions. */
    private volatile java.awt.Point lastMousePos = null;
    private volatile int idleCount = 0;
    private static final int MAX_IDLE_BEFORE_PAUSE = 6; // ~90s of no mouse = pause suggestions

    private static final String AUTOPILOT_PROMPT = """
            You are an AI assistant observing the user's desktop screen. Analyze the screenshot and determine:

            1. WHAT is the user currently doing? (e.g., writing an email, coding, browsing, reading, gaming, idle)
            2. CONTEXT: What app is in focus? What specific content is visible?
            3. OPPORTUNITY: Is there anything you could proactively help with?

            Rules for suggestions:
            - Only suggest if there's genuine value (don't be annoying)
            - Be specific to what you SEE, not generic advice
            - One short suggestion max (1-2 sentences)
            - Phrase as an offer, not a command: "I notice X — want me to Y?"
            - If the user is focused on something complex, don't interrupt
            - If the screen looks idle/locked/screensaver, say: IDLE
            - If you already see a chat window with the bot active, say: IDLE (don't interrupt active conversations)

            Good examples:
            - "I see a compile error in VS Code — want me to help debug it?"
            - "Looks like you're writing a long email — want me to review it for tone?"
            - "I notice you're comparing products — want me to search for reviews?"
            - "You have a calendar reminder coming up in 10 minutes for 'Team Standup'."
            - "I see you're on a spreadsheet with missing data — want me to help fill in column D?"

            Bad examples (too generic, too intrusive):
            - "I see you're using Chrome." (so what?)
            - "You should take a break." (unsolicited life advice)
            - "Let me help you with everything on screen." (too vague)

            Respond with ONLY one of:
            A) A specific, actionable suggestion (1-2 sentences)
            B) IDLE (if nothing useful to suggest)

            Previous context (to avoid repeating): %s""";

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.minsbot.agent.tools.TtsTools ttsTools;

    public AutoPilotService(SystemControlService systemControl,
                            GeminiVisionService geminiVision,
                            ScreenMemoryService screenMemory) {
        this.systemControl = systemControl;
        this.geminiVision = geminiVision;
        this.screenMemory = screenMemory;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Public API
    // ═════════════════════════════════════════════════════════════════════════

    public boolean isEnabled() { return enabled; }

    public void setIntervalSeconds(int seconds) {
        this.intervalMs = Math.max(5, seconds) * 1000;
    }

    public void setCooldownSeconds(int seconds) {
        this.cooldownMs = Math.max(10, seconds) * 1000L;
    }

    /** Start auto-pilot mode. */
    public String start() {
        if (enabled) return "Auto-pilot is already running.";
        enabled = true;
        lastContext = "";
        lastSuggestionTime = 0;
        idleCount = 0;

        pilotThread = new Thread(this::runLoop, "auto-pilot");
        pilotThread.setDaemon(true);
        pilotThread.start();

        log.info("[AutoPilot] Started (interval={}s, cooldown={}s)", intervalMs / 1000, cooldownMs / 1000);
        return "Auto-pilot mode activated. I'll watch your screen and offer help when I notice something useful.";
    }

    /** Stop auto-pilot mode. */
    public String stop() {
        enabled = false;
        Thread t = pilotThread;
        if (t != null) t.interrupt();
        pilotThread = null;
        log.info("[AutoPilot] Stopped");
        return "Auto-pilot mode deactivated.";
    }

    /** Drain pending suggestions (called by ChatController async poll). */
    public List<String> drainSuggestions() {
        List<String> result = new ArrayList<>();
        String s;
        while ((s = suggestions.poll()) != null) result.add(s);
        return result;
    }

    /** Get current status info. */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", enabled);
        status.put("intervalSeconds", intervalMs / 1000);
        status.put("cooldownSeconds", cooldownMs / 1000);
        status.put("lastContext", lastContext);
        status.put("idleCount", idleCount);
        return status;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Main loop
    // ═════════════════════════════════════════════════════════════════════════

    private void runLoop() {
        try { Thread.sleep(3000); } catch (InterruptedException e) { return; }

        while (enabled && !Thread.currentThread().isInterrupted()) {
            try {
                // Check if user is active (mouse moved)
                java.awt.Point currentPos = java.awt.MouseInfo.getPointerInfo().getLocation();
                if (lastMousePos != null && currentPos.equals(lastMousePos)) {
                    idleCount++;
                    if (idleCount >= MAX_IDLE_BEFORE_PAUSE) {
                        // User seems AFK, skip analysis
                        Thread.sleep(intervalMs);
                        continue;
                    }
                } else {
                    idleCount = 0;
                }
                lastMousePos = currentPos;

                // Cooldown check
                long now = System.currentTimeMillis();
                if (now - lastSuggestionTime < cooldownMs) {
                    Thread.sleep(intervalMs);
                    continue;
                }

                // Capture and analyze
                String suggestion = analyzeScreen();
                if (suggestion != null) {
                    suggestions.add("\uD83E\uDD16 **Auto-pilot:** " + suggestion);
                    lastSuggestionTime = System.currentTimeMillis();
                    lastContext = suggestion;
                    // Speak the suggestion aloud
                    if (ttsTools != null) {
                        try { ttsTools.speak(suggestion); }
                        catch (Exception e) { log.debug("[AutoPilot] TTS failed: {}", e.getMessage()); }
                    }
                }

                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                log.warn("[AutoPilot] Error in loop: {}", e.getMessage());
                try { Thread.sleep(intervalMs * 2L); } catch (InterruptedException ie) { break; }
            }
        }
        log.info("[AutoPilot] Loop exited.");
    }

    private String analyzeScreen() {
        try {
            // Take screenshot
            String screenshotStr = systemControl.takeScreenshot();
            if (screenshotStr == null) return null;
            Path screenshotPath = Path.of(screenshotStr);

            String prompt = String.format(AUTOPILOT_PROMPT,
                    lastContext.isBlank() ? "(none)" : lastContext);

            // Use Gemini vision to analyze
            String analysis = geminiVision.analyze(screenshotPath, prompt);
            if (analysis == null || analysis.isBlank()) return null;

            String trimmed = analysis.trim();

            // Filter out IDLE responses
            if (trimmed.equalsIgnoreCase("IDLE") || trimmed.equalsIgnoreCase("idle.")) return null;

            // Filter out responses that are too similar to the last suggestion
            if (isTooSimilar(trimmed, lastContext)) return null;

            // Filter out responses that are too long (probably not a clean suggestion)
            if (trimmed.length() > 300) {
                trimmed = trimmed.substring(0, 297) + "...";
            }

            log.info("[AutoPilot] Suggestion: {}", trimmed);
            return trimmed;
        } catch (Exception e) {
            log.debug("[AutoPilot] Analysis failed: {}", e.getMessage());
            return null;
        }
    }

    private boolean isTooSimilar(String a, String b) {
        if (b == null || b.isBlank()) return false;
        // Simple word overlap check
        Set<String> wordsA = new HashSet<>(Arrays.asList(a.toLowerCase().split("\\W+")));
        Set<String> wordsB = new HashSet<>(Arrays.asList(b.toLowerCase().split("\\W+")));
        if (wordsA.isEmpty() || wordsB.isEmpty()) return false;
        Set<String> intersection = new HashSet<>(wordsA);
        intersection.retainAll(wordsB);
        double overlap = (double) intersection.size() / Math.min(wordsA.size(), wordsB.size());
        return overlap > 0.6;
    }
}
