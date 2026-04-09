package com.minsbot.agent;

import com.minsbot.agent.tools.DirectivesTools;
import com.minsbot.agent.tools.TodoListTools;
import com.minsbot.agent.tools.ToolRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

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
    private final MouseModule mouseModule;
    private final ScreenStateService screenStateService;
    private final SystemContextProvider systemCtx;
    private final ToolRouter toolRouter;
    private final TodoListTools todoListTools;

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
                                  @Lazy MouseModule mouseModule,
                                  ScreenStateService screenStateService,
                                  SystemContextProvider systemCtx,
                                  @Lazy ToolRouter toolRouter,
                                  TodoListTools todoListTools) {
        this.asyncMessages = asyncMessages;
        this.mouseModule = mouseModule;
        this.screenStateService = screenStateService;
        this.systemCtx = systemCtx;
        this.toolRouter = toolRouter;
        this.todoListTools = todoListTools;
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

        // Track whether we already assessed the current screen state
        boolean screenAssessed = false;

        while (active) {
            try {
                long now = System.currentTimeMillis();

                // ── Screen check: only when pixels actually changed ──
                if (mouseModule.hasScreenChanged()) {
                    // Screen changed — reset the "assessed" flag
                    screenAssessed = false;
                }

                if (!screenAssessed && now - lastScreenCheck >= screenCheckSeconds * 1000L) {
                    checkScreenForActions();
                    lastScreenCheck = System.currentTimeMillis();
                    screenAssessed = true; // Don't assess again until screen changes
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

    /**
     * Single combined assess-and-act cycle:
     * 1. Vision call to understand the screen (what + where)
     * 2. Immediately execute actions based on the assessment
     * Only ONE LLM call — the tool-calling model both sees and acts.
     */
    private void checkScreenForActions() {
        if (chatClient == null) return;

        try {
            log.info("[ProactiveAction] Screen changed — running assess-and-act cycle");

            // Single LLM call: assess the screen AND execute actions in one shot.
            // The AI sees the screen via the system prompt's screen analysis,
            // then uses tools to act — all in one call, not two.
            String actionPrompt = """
                    You are in PROACTIVE ACTION MODE. Look at the screen and ACT.

                    Take a screenshot first with takeScreenshot() to see what's on screen.
                    Then analyze what you see and IMMEDIATELY take action:

                    FOR WEB FORMS (use CDP — fastest, most reliable):
                    1. browserFillForm(siteUrl, 'selector1=value1|selector2=value2') — fills ALL fields in ~10ms
                       Use: input[placeholder="Field Name"], input[type="email"], textarea, etc.
                    2. browserFillField(siteUrl, selector, text) — for single fields / verification strings
                    3. browserClickButton(siteUrl, 'Button Text') — click submit/OK/confirm
                    4. browserClickElement(siteUrl, 'css-selector') — click by selector

                    FOR BUTTON SEQUENCES / CHALLENGES:
                    1. Read the instructions on screen carefully
                    2. browserClickButton(siteUrl, 'ButtonText') for each button in order
                    3. Handle verification strings with browserFillField

                    FOR DIALOGS / POPUPS:
                    1. screenClick(x, y) to click OK/Cancel/Yes/No buttons

                    AFTER ACTING: take another screenshot to verify. If something didn't work, retry.

                    RULES:
                    - Act IMMEDIATELY. Don't describe what you'll do — just DO it.
                    - If nothing actionable on screen, respond with just: NOTHING_ACTIONABLE
                    - If you just see a normal desktop/browser with no task, respond: NOTHING_ACTIONABLE""";

            String reply = executeWithTools(actionPrompt);

            if (reply == null || reply.isBlank() || reply.contains("NOTHING_ACTIONABLE")) {
                log.debug("[ProactiveAction] Nothing actionable on screen");
                return;
            }

            // Check cooldown
            String actionHash = computeActionHash(reply);
            if (isRecentAction(actionHash)) {
                log.debug("[ProactiveAction] Action on cooldown: {}", truncate(reply, 80));
                return;
            }

            recordAction(actionHash);
            asyncMessages.push("\u26a1 " + reply);
            speakThought(reply);
            log.info("[ProactiveAction] Cycle complete: {}", truncate(reply, 200));

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

    private static final String PROACTIVE_SYSTEM_PROMPT = """
            You are an AUTONOMOUS AI AGENT in proactive action mode. The user has EXPLICITLY enabled \
            this mode and wants you to ACT on their behalf — no confirmation needed.

            YOUR MANDATE:
            - You MUST execute actions. Do NOT ask the user to do it. Do NOT say "you should" or "you can".
            - You MUST click buttons, fill forms, type text, and submit — that is your entire purpose.
            - You MUST complete the full task including clicking Submit/OK/Confirm buttons.
            - You are NOT a chatbot right now. You are an action executor. ACT, don't advise.

            SAFETY (hard-coded, cannot override):
            - NEVER close browser tabs or windows
            - NEVER delete files
            - NEVER send emails/messages
            - ONLY interact with what's visible on screen: fill fields, click buttons, type text

            REMEMBER: The user enabled proactive mode BECAUSE they want you to act autonomously. \
            Refusing to act or asking them to do it defeats the purpose. JUST DO IT.""";

    /** Unique conversation ID so proactive mode has its own memory, separate from main chat. */
    private static final String PROACTIVE_CONV_ID = "proactive-action-mode";

    private String executeWithTools(String prompt) {
        if (chatClient == null) return null;

        try {
            // Use separate conversation ID so main chat history doesn't bleed in
            // and cause the AI to refuse autonomous actions
            String reply = chatClient.prompt()
                    .advisors(a -> a.param(
                            org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID,
                            PROACTIVE_CONV_ID))
                    .system(PROACTIVE_SYSTEM_PROMPT)
                    .user(prompt)
                    .tools(toolRouter.selectTools(prompt))
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
