package com.minsbot.agent;

import com.minsbot.agent.tools.DirectivesTools;
import com.minsbot.agent.tools.TodoListTools;
import com.minsbot.agent.tools.ToolRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Proactive Action Mode: Jarvis-like assistant that continuously monitors
 * the screen, pending tasks, and directives, then takes action automatically.
 * <p>
 * Unlike autonomous mode (runs only when idle for 60s), this runs continuously
 * while enabled, acting as a real-time assistant that fills forms, clicks buttons,
 * dismisses dialogs, and completes pending tasks without the user asking.
 */
@Service
public class ProactiveActionService {

    private static final Logger log = LoggerFactory.getLogger(ProactiveActionService.class);

    private final AsyncMessageService asyncMessages;
    private final ScreenStateService screenStateService;
    private final SystemContextProvider systemCtx;
    private final SystemControlService systemControl;
    private final ToolRouter toolRouter;
    private final TodoListTools todoListTools;
    private final GeminiVisionService geminiVision;

    @Autowired(required = false)
    private ChatClient chatClient;

    @Autowired(required = false)
    private com.minsbot.agent.tools.TtsTools ttsTools;

    private volatile boolean active = false;
    private volatile boolean running = false;
    private Thread proactiveThread;

    // Configurable intervals
    @Value("${app.proactive-action.screen-check-seconds:15}")
    private int screenCheckSeconds;

    @Value("${app.proactive-action.task-check-seconds:30}")
    private int taskCheckSeconds;

    @Value("${app.proactive-action.directive-check-seconds:60}")
    private int directiveCheckSeconds;

    // Tracking state
    private volatile long lastScreenCheck = 0;
    private volatile long lastTaskCheck = 0;
    private volatile long lastDirectiveCheck = 0;
    private volatile long lastUserActivityTime = System.currentTimeMillis();

    /** Recent actions taken — used to avoid repeating the same action within the cooldown period. */
    private final ConcurrentLinkedDeque<ActionRecord> recentActions = new ConcurrentLinkedDeque<>();
    private static final int MAX_RECENT_ACTIONS = 50;
    private static final long ACTION_COOLDOWN_MS = 60_000; // 60 seconds

    /** Tracks a recent action to prevent repetition. */
    private record ActionRecord(long timestamp, String actionHash) {}

    private static final String SCREEN_CHECK_PROMPT = """
            You are a proactive assistant monitoring the user's screen.
            Look at this screenshot and identify ANY actionable items:
            - Dialog boxes waiting for input (OK/Cancel/Yes/No)
            - Error messages that need dismissing
            - Forms with empty fields that should be filled
            - Notifications that need acknowledgment
            - Downloads completed that need action
            - Windows that appear stuck or frozen
            - Any other situation where you can help

            If you find something actionable, describe what you see and what action to take.
            If the screen looks normal with nothing actionable, respond with exactly: NOTHING_ACTIONABLE

            Be conservative:
            - Only act on things that clearly need attention
            - Do NOT interrupt the user's active work (typing, reading, coding)
            - Do NOT click on things the user is currently interacting with
            - If the user appears to be actively working in an application, respond NOTHING_ACTIONABLE""";

    private static final String TASK_CHECK_PROMPT = """
            You are a proactive assistant. The user has the following pending tasks:

            %s

            Review these tasks and determine which ones you can complete RIGHT NOW without user input.
            Only pick tasks that are:
            - Clearly defined with enough information to act on
            - Doable with the tools you have (file operations, web search, system commands)
            - Safe to do without confirmation (no destructive actions)

            If there's a task you can do now, describe what you'll do and then do it.
            If no tasks can be done proactively, respond with exactly: NOTHING_ACTIONABLE""";

    private static final String DIRECTIVE_CHECK_PROMPT = """
            You are a proactive assistant. The user has set these directives:

            %s

            Check if any directive has actionable work you can do RIGHT NOW:
            - Research tasks that haven't been started
            - Monitoring tasks that need checking
            - Recurring tasks that are due
            - Information gathering that can be done in the background

            If there's something actionable, describe what you'll do and then do it.
            If nothing needs action right now, respond with exactly: NOTHING_ACTIONABLE""";

    public ProactiveActionService(AsyncMessageService asyncMessages,
                                  ScreenStateService screenStateService,
                                  SystemContextProvider systemCtx,
                                  SystemControlService systemControl,
                                  @org.springframework.context.annotation.Lazy ToolRouter toolRouter,
                                  TodoListTools todoListTools,
                                  GeminiVisionService geminiVision) {
        this.asyncMessages = asyncMessages;
        this.screenStateService = screenStateService;
        this.systemCtx = systemCtx;
        this.systemControl = systemControl;
        this.toolRouter = toolRouter;
        this.todoListTools = todoListTools;
        this.geminiVision = geminiVision;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Public API
    // ═════════════════════════════════════════════════════════════════════════

    public boolean isActive() { return active; }
    public boolean isRunning() { return running; }

    public String start() {
        if (active) return "Proactive action mode is already running.";
        if (chatClient == null) return "Cannot start proactive action mode — no AI model configured.";

        active = true;
        lastScreenCheck = 0;
        lastTaskCheck = 0;
        lastDirectiveCheck = 0;
        recentActions.clear();

        proactiveThread = new Thread(this::proactiveLoop, "proactive-action");
        proactiveThread.setDaemon(true);
        proactiveThread.start();

        log.info("[ProactiveAction] Started (screen={}s, tasks={}s, directives={}s)",
                screenCheckSeconds, taskCheckSeconds, directiveCheckSeconds);
        return "Proactive action mode activated. I'll continuously monitor your screen, "
                + "check pending tasks, and act on directives automatically. "
                + "Screen check every " + screenCheckSeconds + "s, "
                + "task check every " + taskCheckSeconds + "s, "
                + "directive check every " + directiveCheckSeconds + "s.";
    }

    public String stop() {
        if (!active) return "Proactive action mode is not active.";
        active = false;
        if (proactiveThread != null) proactiveThread.interrupt();
        proactiveThread = null;
        log.info("[ProactiveAction] Stopped");
        return "Proactive action mode deactivated.";
    }

    /** Called by ChatService when user sends a message — tracks last activity. */
    public void notifyUserActivity() {
        lastUserActivityTime = System.currentTimeMillis();
    }

    public void setScreenCheckSeconds(int seconds) {
        this.screenCheckSeconds = Math.max(5, seconds);
    }

    public void setTaskCheckSeconds(int seconds) {
        this.taskCheckSeconds = Math.max(10, seconds);
    }

    public void setDirectiveCheckSeconds(int seconds) {
        this.directiveCheckSeconds = Math.max(15, seconds);
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("active", active);
        status.put("running", running);
        status.put("screenCheckSeconds", screenCheckSeconds);
        status.put("taskCheckSeconds", taskCheckSeconds);
        status.put("directiveCheckSeconds", directiveCheckSeconds);
        status.put("lastScreenCheck", lastScreenCheck);
        status.put("lastTaskCheck", lastTaskCheck);
        status.put("lastDirectiveCheck", lastDirectiveCheck);
        status.put("recentActionsCount", recentActions.size());
        return status;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Main proactive loop
    // ═════════════════════════════════════════════════════════════════════════

    private void proactiveLoop() {
        running = true;
        log.info("[ProactiveAction] Loop started");

        // Initial warm-up delay
        try { Thread.sleep(5000); } catch (InterruptedException e) {
            running = false;
            return;
        }

        while (active) {
            try {
                long now = System.currentTimeMillis();

                // Skip if user was active very recently (within 5 seconds)
                if (now - lastUserActivityTime < 5000) {
                    Thread.sleep(2000);
                    continue;
                }

                // Screen check — most frequent
                if (now - lastScreenCheck >= screenCheckSeconds * 1000L) {
                    checkScreenForActions();
                    lastScreenCheck = System.currentTimeMillis();
                }

                // Task check — medium frequency
                if (now - lastTaskCheck >= taskCheckSeconds * 1000L) {
                    checkPendingTasks();
                    lastTaskCheck = System.currentTimeMillis();
                }

                // Directive check — least frequent
                if (now - lastDirectiveCheck >= directiveCheckSeconds * 1000L) {
                    checkDirectives();
                    lastDirectiveCheck = System.currentTimeMillis();
                }

                Thread.sleep(2000); // Poll every 2s
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("[ProactiveAction] Error in loop: {}", e.getMessage());
                try { Thread.sleep(5000); } catch (InterruptedException ie) { break; }
            }
        }
        running = false;
        log.info("[ProactiveAction] Loop ended");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Screen check — look for actionable items on screen
    // ═════════════════════════════════════════════════════════════════════════

    private void checkScreenForActions() {
        if (chatClient == null) return;

        try {
            // Take screenshot
            String result = systemControl.takeScreenshot();
            if (result == null || !result.startsWith("Screenshot saved:")) {
                log.debug("[ProactiveAction] Screenshot failed: {}", result);
                return;
            }

            String pathStr = result.replace("Screenshot saved: ", "").trim();
            Path screenshotPath = Paths.get(pathStr);

            if (!Files.exists(screenshotPath)) return;

            // Analyze with Gemini vision (fast, doesn't use the main ChatClient)
            String analysis = null;
            if (geminiVision.isAvailable()) {
                analysis = geminiVision.analyzeQuick(screenshotPath, SCREEN_CHECK_PROMPT);
            }

            if (analysis == null || analysis.isBlank()) {
                log.debug("[ProactiveAction] Screen analysis returned empty");
                return;
            }

            String trimmed = analysis.trim();
            if (trimmed.equalsIgnoreCase("NOTHING_ACTIONABLE")
                    || trimmed.contains("NOTHING_ACTIONABLE")) {
                log.debug("[ProactiveAction] Screen check: nothing actionable");
                return;
            }

            // Check cooldown — don't repeat the same action
            String actionHash = computeActionHash(trimmed);
            if (isRecentAction(actionHash)) {
                log.debug("[ProactiveAction] Screen action on cooldown, skipping: {}", truncate(trimmed, 80));
                return;
            }

            log.info("[ProactiveAction] Screen found actionable: {}", truncate(trimmed, 200));

            // Use AI with tools to take action
            String actionPrompt = "You are in proactive action mode. Based on screen analysis, take action:\n\n"
                    + trimmed + "\n\nExecute the appropriate action using the tools available. "
                    + "Be precise and conservative. Describe what you did.";

            String reply = executeWithTools(actionPrompt);
            if (reply != null && !reply.isBlank()) {
                recordAction(actionHash);
                asyncMessages.push("[Proactive] " + reply);
                speakThought(reply);
                log.info("[ProactiveAction] Screen action completed: {}", truncate(reply, 200));
            }

        } catch (Exception e) {
            log.warn("[ProactiveAction] Screen check error: {}", e.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Task check — look for pending tasks to complete
    // ═════════════════════════════════════════════════════════════════════════

    private void checkPendingTasks() {
        if (chatClient == null) return;

        try {
            String pending = todoListTools.getPendingTasks();
            if (pending == null || pending.isBlank()
                    || pending.contains("No pending tasks")
                    || pending.contains("No todolist.txt")) {
                log.debug("[ProactiveAction] No pending tasks");
                return;
            }

            // Check cooldown for task-based actions
            String actionHash = "tasks:" + computeActionHash(pending);
            if (isRecentAction(actionHash)) {
                log.debug("[ProactiveAction] Task check on cooldown");
                return;
            }

            log.info("[ProactiveAction] Found pending tasks, analyzing...");

            String prompt = TASK_CHECK_PROMPT.formatted(pending);
            String reply = executeWithTools(prompt);

            if (reply != null && !reply.isBlank()
                    && !reply.contains("NOTHING_ACTIONABLE")) {
                recordAction(actionHash);
                asyncMessages.push("[Proactive - Tasks] " + reply);
                speakThought(reply);
                log.info("[ProactiveAction] Task action completed: {}", truncate(reply, 200));
            }

        } catch (Exception e) {
            log.warn("[ProactiveAction] Task check error: {}", e.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Directive check — look for actionable directives
    // ═════════════════════════════════════════════════════════════════════════

    private void checkDirectives() {
        if (chatClient == null) return;

        try {
            String directives = DirectivesTools.loadDirectivesForPrompt();
            if (directives == null || directives.isBlank()) {
                log.debug("[ProactiveAction] No directives");
                return;
            }

            // Check cooldown for directive-based actions
            String actionHash = "directives:" + computeActionHash(directives);
            if (isRecentAction(actionHash)) {
                log.debug("[ProactiveAction] Directive check on cooldown");
                return;
            }

            log.info("[ProactiveAction] Checking directives for actionable work...");

            String prompt = DIRECTIVE_CHECK_PROMPT.formatted(directives);
            String reply = executeWithTools(prompt);

            if (reply != null && !reply.isBlank()
                    && !reply.contains("NOTHING_ACTIONABLE")) {
                recordAction(actionHash);
                asyncMessages.push("[Proactive - Directives] " + reply);
                speakThought(reply);
                log.info("[ProactiveAction] Directive action completed: {}", truncate(reply, 200));
            }

        } catch (Exception e) {
            log.warn("[ProactiveAction] Directive check error: {}", e.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // AI execution with tools
    // ═════════════════════════════════════════════════════════════════════════

    private String executeWithTools(String prompt) {
        if (chatClient == null) return null;

        try {
            String reply = chatClient.prompt()
                    .system(systemCtx.buildSystemMessage())
                    .user(prompt)
                    .tools(toolRouter.selectToolsForAutonomous(prompt))
                    .call()
                    .content();
            return reply;
        } catch (Exception e) {
            log.warn("[ProactiveAction] AI call failed: {}", e.getMessage());
            return null;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Action deduplication / cooldown
    // ═════════════════════════════════════════════════════════════════════════

    private String computeActionHash(String text) {
        // Simple hash: first 100 chars lowercased, normalized whitespace
        String normalized = text.toLowerCase().replaceAll("\\s+", " ").trim();
        if (normalized.length() > 100) normalized = normalized.substring(0, 100);
        return String.valueOf(normalized.hashCode());
    }

    private boolean isRecentAction(String actionHash) {
        long now = System.currentTimeMillis();
        // Clean up old entries
        recentActions.removeIf(r -> now - r.timestamp() > ACTION_COOLDOWN_MS);
        // Check if this action was taken recently
        return recentActions.stream().anyMatch(r -> r.actionHash().equals(actionHash));
    }

    private void recordAction(String actionHash) {
        recentActions.addLast(new ActionRecord(System.currentTimeMillis(), actionHash));
        while (recentActions.size() > MAX_RECENT_ACTIONS) {
            recentActions.pollFirst();
        }
    }

    /** Speak proactive thoughts aloud via TTS. */
    private void speakThought(String text) {
        if (ttsTools == null || text == null || text.isBlank()) return;
        try {
            String spoken = text.length() > 300 ? text.substring(0, 300) : text;
            ttsTools.speak(spoken);
        } catch (Exception e) {
            log.debug("[ProactiveAction] TTS failed: {}", e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "(null)";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
