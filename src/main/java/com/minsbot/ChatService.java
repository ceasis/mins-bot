package com.minsbot;

import com.minsbot.agent.AsyncMessageService;
import com.minsbot.agent.PcAgentService;
import com.minsbot.agent.ScreenStateService;
import com.minsbot.agent.SystemContextProvider;
import com.minsbot.agent.WorkingSoundService;
import com.minsbot.agent.tools.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpClient.Version;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final String AUDIO_RESULT_PREFIX = "__AUDIO_RESULT__";

    /** Identity / small-talk — skip task planning and live-screen prep. */
    private static final Pattern SIMPLE_CONVERSATIONAL = Pattern.compile(
            "(?i)^(hi|hello|hey|howdy|yo|sup|morning|afternoon|evening)\\b[!.?\\s]*$"
                    + "|^(thanks?|thank you|thx|ty)\\b[!.?\\s]*$"
                    + "|^(ok|okay|cool|nice|great|got it|sounds good)\\b[!.?\\s]*$"
                    + "|^(bye|goodbye|cya|see ya|see you)\\b[!.?\\s]*$"
                    + "|\\b(what'?s|what is|whats)\\s+your\\s+name\\b"
                    + "|\\bwho\\s+are\\s+you\\b"
                    + "|\\bwhat\\s+should\\s+i\\s+call\\s+you\\b"
                    + "|\\bhow\\s+are\\s+you\\b"
                    + "|\\bwhat\\s+can\\s+you\\s+do\\b"
                    + "|\\bwhat\\s+do\\s+you\\s+do\\b"
                    + "|\\btell\\s+me\\s+about\\s+yourself\\b");

    /**
     * If the message is long or mentions UI/automation, live screen context helps.
     * Short chat-only questions skip capture when {@code app.chat.live-screen-on-message} is on.
     */
    private static final Pattern SCREEN_OR_AUTOMATION_HINT = Pattern.compile(
            "(?i)\\b(screen|screenshot|desktop|window|browser|chrome|firefox|edge|safari|monitor|display|what'?s? on my\\s+screen"
                    + "|my\\s+screen|this\\s+(window|tab|page|app)|cdp|devtools"
                    + "|click|double[- ]?click|right[- ]?click|cursor|mouse|keyboard|type\\b|keypress|shortcut"
                    + "|\\bopen\\b|\\blaunch\\b|\\bstart\\b|\\brun\\b|\\bclose\\b|\\bminimize\\b|\\bmaximize\\b"
                    + "|scroll|select\\b|drag|paste|clipboard|save\\b|download|upload|install|uninstall"
                    + "|folder|directory|file\\b|path\\b|drive\\b|notepad|powershell|cmd\\b|terminal|excel|word|outlook"
                    + "|https?://|localhost:\\d+|\\bjira\\b|\\bslack\\b|\\bteams\\b)\\b");

    private static final String PLANNING_PROMPT = """
            You are a task planner for a PC assistant bot. Analyze the user's request and output a \
            numbered checklist plan of the steps needed to complete the task. The LAST step must ALWAYS \
            be a verification step.

            Rules:
            - Output ONLY the numbered plan. No greetings, no explanations, no filler.
            - Use this exact format (unicode box characters):
              ⬜ 1. First action step
              ⬜ 2. Second action step
              ⬜ 3. Verify — [how to confirm it worked]
            - Keep steps short and specific (max 10 words each).
            - Always end with a verification step: "Verify — read file back", "Verify — take screenshot", etc.
            - For simple questions, greetings, or tasks that need no tools, respond with just: SKIP
            - Max 10 steps. Combine related actions into one step if needed.

            Examples:
            User: "open google and search for bose speakers"
            ⬜ 1. Open google.com in Chrome
            ⬜ 2. Type "bose speakers" in the search box and press Enter
            ⬜ 3. Verify — take screenshot to confirm search results

            User: "my wife is katherine, email is dai@gmail.com"
            ⬜ 1. Read current personal_config.txt
            ⬜ 2. Update Partner/spouse section with name and email
            ⬜ 3. Save the updated file
            ⬜ 4. Verify — read file back to confirm changes saved

            User: "what time is it?"
            SKIP

            User: "prepare my morning briefing"
            ⬜ 1. Fetch unread emails from Gmail
            ⬜ 2. Fetch today's calendar events
            ⬜ 3. Get weather forecast for my location
            ⬜ 4. Summarize everything into a concise briefing
            ⬜ 5. Speak the briefing aloud
            ⬜ 6. Verify — confirm all data sources responded

            User: "compare cloud GPU pricing for AWS, Azure, and GCP, create an Excel and PDF summary"
            ⬜ 1. Search web for AWS GPU instance pricing (24GB VRAM)
            ⬜ 2. Search web for Azure GPU instance pricing (24GB VRAM)
            ⬜ 3. Search web for GCP GPU instance pricing (24GB VRAM)
            ⬜ 4. Create Excel spreadsheet with comparison columns
            ⬜ 5. Write pricing data into Excel cells
            ⬜ 6. Create PDF summary report on Desktop
            ⬜ 7. Speak the summary aloud
            ⬜ 8. Verify — read Excel back to confirm data
            """;

    /**
     * Planner for background agents (search / files / HTTP fetch / Playwright only). Does not touch main chat UI or todolist.
     */
    private static final String AGENT_PLANNING_PROMPT = """
            You are a task planner for a BACKGROUND AGENT with LIMITED tools only:
            web search (searchWeb), file read/write/list, Excel/Word/PDF tools, HTTP page fetch (readWebPage-style), \
            and headless Playwright (browsePage, images, links — no visible browser, no CDP, no screen control).

            Output ONLY a numbered checklist. No greetings. Use this format:
              ⬜ 1. First step
              ⬜ 2. Next step
              ⬜ 3. Verify — [e.g. re-read file, fetch URL again, or searchWeb to confirm]

            Rules:
            - LAST step must be Verify — using only allowed tools (never "take screenshot" or "click on screen").
            - Max 6 steps; keep each under 12 words.
            - For trivial missions (single search or single file read), respond with: SKIP

            Example:
            User: Summarize top news on topic X from the web
            ⬜ 1. searchWeb for topic X news
            ⬜ 2. readWebPage or browsePage top result URL
            ⬜ 3. Verify — searchWeb again with narrower query if empty
            """;

    /** Shown when user says "quit"; also used by ChatController to add quitCountdownSeconds to response. */
    public static final String QUIT_REPLY = "Quit Mins Bot?";

    private final TranscriptService transcriptService;
    private final PcAgentService pcAgent;
    private final SystemContextProvider systemCtx;
    private final MinsBotQuitService quitService;

    private final FileTools fileTools;              // needed for setAsyncCallback()
    private final ToolExecutionNotifier toolNotifier;
    private final WorkingSoundService workingSound;
    private final ToolRouter toolRouter;
    private final TtsTools ttsTools;
    private final ScreenStateService screenStateService;
    private final com.minsbot.agent.tools.ScreenWatchingTools screenWatchingTools;

    /** Spring AI ChatClient — null when no API key is configured. Swappable at runtime. */
    @Autowired(required = false)
    private volatile ChatClient chatClient;

    /** Spring AI ChatMemory — null when Spring AI is not active. */
    @Autowired(required = false)
    private ChatMemory chatMemory;

    /** Allow tools to swap the active ChatClient at runtime (e.g., switch to Ollama). */
    public void setChatClient(ChatClient newClient) {
        this.chatClient = newClient;
    }

    public ChatClient getChatClient() {
        return this.chatClient;
    }

    private final AsyncMessageService asyncMessages;

    public ChatService(TranscriptService transcriptService,
                       PcAgentService pcAgent,
                       SystemContextProvider systemCtx,
                       MinsBotQuitService quitService,
                       FileTools fileTools,
                       ToolExecutionNotifier toolNotifier,
                       WorkingSoundService workingSound,
                       ToolRouter toolRouter,
                       AsyncMessageService asyncMessages,
                       TtsTools ttsTools,
                       ScreenStateService screenStateService,
                       com.minsbot.agent.tools.ScreenWatchingTools screenWatchingTools) {
        this.transcriptService = transcriptService;
        this.pcAgent = pcAgent;
        this.systemCtx = systemCtx;
        this.quitService = quitService;
        this.fileTools = fileTools;
        this.toolNotifier = toolNotifier;
        this.workingSound = workingSound;
        this.toolRouter = toolRouter;
        this.asyncMessages = asyncMessages;
        this.ttsTools = ttsTools;
        this.screenStateService = screenStateService;
        this.screenWatchingTools = screenWatchingTools;
    }

    @PostConstruct
    public void diagnostics() {
        String cwd = System.getProperty("user.dir");
        File secretsFile = new File(cwd, "application-secrets.properties");
        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║  [ChatService] Working directory: {}", cwd);
        log.info("║  [ChatService] Secrets file exists: {} ({})", secretsFile.exists(), secretsFile.getAbsolutePath());
        log.info("║  [ChatService] ChatClient injected: {}", chatClient != null);
        log.info("║  [ChatService] OpenAI API key (audio): {}", (openAiApiKey != null && !openAiApiKey.isBlank()) ? "SET" : "NOT SET");
        log.info("╚══════════════════════════════════════════════════════════════╝");

        // Wire up dynamic sound switching: tool notifications → sound phase changes
        toolNotifier.setSoundListener(workingSound::onToolExecution);

        // Seed Spring AI ChatMemory with transcript history so AI remembers previous conversations
        seedChatMemory();

        // ═══ Start the main agent loop ═══
        startMainLoop();
    }

    /**
     * Main agent loop — runs continuously as long as the app is alive.
     * Checks for user messages in the queue; if none, observes the screen autonomously.
     * 100ms delay between iterations.
     */
    private void startMainLoop() {
        mainLoopThread = new Thread(() -> {
            // Wait for Spring context + JavaFX to fully initialize
            try { Thread.sleep(8000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            log.info("[MainLoop] Agent main loop started.");

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // Check if AI client is available
                    if (chatClient == null) {
                        Thread.sleep(1000);
                        continue;
                    }

                    // Poll for user message
                    String userMessage = userMessageQueue.poll();

                    if (userMessage != null) {
                        // ── User message: process as instruction ──
                        mainLoopBusy = true;
                        lastActivityTime = System.currentTimeMillis();
                        log.info("[MainLoop] Processing user message: {}",
                                userMessage.substring(0, Math.min(userMessage.length(), 80)));
                        processUserMessage(userMessage);
                        mainLoopBusy = false;
                    } else {
                        // ── No user message: observe screen autonomously ──
                        // Only run autonomous observation if idle long enough
                        long idleMs = System.currentTimeMillis() - lastActivityTime;
                        if (idleMs >= 5000 && !mainLoopBusy) {
                            mainLoopBusy = true;
                            runAutonomousObservation();
                            mainLoopBusy = false;
                        }
                    }

                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    if (stopRequested) {
                        // Stop button interrupted the sleep — clear flag and continue loop
                        stopRequested = false;
                        Thread.interrupted(); // clear interrupted status
                        mainLoopBusy = false;
                    } else {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } catch (Exception e) {
                    log.error("[MainLoop] Error in main loop: {}", e.getMessage(), e);
                    mainLoopBusy = false;
                    try { Thread.sleep(2000); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            log.info("[MainLoop] Agent main loop stopped.");
        }, "agent-main-loop");
        mainLoopThread.setDaemon(true);
        mainLoopThread.start();
    }

    /**
     * Process a user message through the AI with planning, screen analysis, and tool calling.
     */
    private void processUserMessage(String trimmed) {
        stopRequested = false; // reset at start of each message

        Consumer<String> asyncCallback = result -> {
            transcriptService.save("BOT(agent)", result);
            asyncMessages.push(result);
        };

        try {
            fileTools.setAsyncCallback(asyncCallback);

            boolean willPlan = planningEnabled && needsPlanning(trimmed);
            boolean willScreen = liveScreenOnMessage && needsLiveScreenForMessage(trimmed);
            if (willPlan || willScreen) {
                asyncMessages.push(prepAcknowledgement(willPlan, willScreen));
            }
            workingSound.start();

            CompletableFuture<String> planFuture = willPlan
                    ? CompletableFuture.supplyAsync(() -> executePlanningForMessage(trimmed), chatPrepExecutor)
                    : CompletableFuture.completedFuture(null);

            CompletableFuture<String> screenFuture = willScreen
                    ? CompletableFuture.supplyAsync(() -> {
                        try {
                            return screenStateService.captureAndAnalyze(trimmed);
                        } catch (Exception e) {
                            log.warn("[MainLoop] Screen analysis failed: {}", e.getMessage());
                            return null;
                        }
                    }, chatPrepExecutor)
                    : CompletableFuture.completedFuture(null);

            String generatedPlan = planFuture.join();
            String screenAnalysis = screenFuture.join();

            String systemMessage = systemCtx.buildSystemMessage(trimmed);

            // Resume pending tasks
            if (generatedPlan == null && isResumeCommand(trimmed)) {
                try {
                    java.nio.file.Path todoPath = java.nio.file.Paths.get(
                            System.getProperty("user.home"), "mins_bot_data", "todolist.txt");
                    if (java.nio.file.Files.exists(todoPath)) {
                        String todoContent = java.nio.file.Files.readString(todoPath);
                        if (todoContent.contains("[PENDING]")) {
                            String[] blocks = todoContent.split("--- Task:");
                            for (int b = blocks.length - 1; b >= 0; b--) {
                                if (blocks[b].contains("[PENDING]")) {
                                    generatedPlan = "--- Task:" + blocks[b].trim();
                                    break;
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("[MainLoop] Could not read todolist.txt: {}", e.getMessage());
                }
            }

            // Inject plan
            if (generatedPlan != null) {
                systemMessage += "\n\n══ YOUR PLAN (execute ALL steps, do NOT stop mid-plan) ══\n"
                        + generatedPlan + "\n"
                        + "══ MANDATORY RESPONSE FORMAT:\n"
                        + "Your FINAL response after completing all steps MUST look EXACTLY like this:\n"
                        + "✅ 1. [what you did for step 1]\n"
                        + "✅ 2. [what you did for step 2]\n"
                        + "✅ 3. [what you did for step 3]\n"
                        + "✅ 4. [verification result]\n\n"
                        + "That's it. No extra text. No \"feel free to ask\". No \"let me know\". Just the checklist.\n"
                        + "If a step is NOT done yet, show ⬜ and keep calling tools until it's done.\n"
                        + "Call markStepDone(N) after each step completes.\n"
                        + "Start executing step 1 NOW. ══\n";
            }

            if (screenAnalysis != null && !screenAnalysis.isBlank()) {
                systemMessage += "\n\nLIVE SCREEN ANALYSIS (captured BEFORE your actions — "
                        + "this shows what was on screen when the user sent their message):\n"
                        + screenAnalysis + "\n"
                        + "\nCRITICAL: Use ONLY the names from this analysis for initial actions. "
                        + "AFTER any action that changes the screen, take a fresh takeScreenshot().\n";
            }

            // Watch mode observation
            if (screenWatchingTools.isWatching()) {
                String watchObs = screenWatchingTools.getLatestObservation();
                if (watchObs != null && !watchObs.isBlank()) {
                    systemMessage += "\n\nWATCH MODE IS ACTIVE — Latest screen observation:\n"
                            + watchObs + "\n";
                }
            }

            // Call AI
            final String finalSystemMessage = systemMessage;
            for (int attempt = 1; attempt <= 3; attempt++) {
                // Check if a new user message arrived or stop was requested
                if (!userMessageQueue.isEmpty()) {
                    log.info("[MainLoop] New user message arrived — interrupting current AI call");
                    workingSound.stop();
                    return;
                }
                if (stopRequested) {
                    log.info("[MainLoop] Stop requested — aborting AI call");
                    workingSound.stop();
                    asyncMessages.push("Stopped.");
                    return;
                }
                try {
                    String reply = chatClient.prompt()
                            .system(finalSystemMessage)
                            .user(trimmed)
                            .tools(toolRouter.selectTools(trimmed))
                            .call()
                            .content();

                    if (stopRequested) {
                        log.info("[MainLoop] Stop requested after AI call — discarding reply");
                        workingSound.stop();
                        asyncMessages.push("Stopped.");
                        return;
                    }

                    workingSound.stop();
                    if (reply != null && !reply.isBlank()) {
                        transcriptService.save("BOT", reply);
                        asyncMessages.push(reply);
                        autoSpeak(reply);
                    }
                    return;
                } catch (Exception e) {
                    if (stopRequested) {
                        log.info("[MainLoop] Stop requested (interrupted AI call)");
                        workingSound.stop();
                        asyncMessages.push("Stopped.");
                        return;
                    }
                    boolean isNetworkError = e instanceof org.springframework.web.client.ResourceAccessException
                            || (e.getCause() != null && (e.getCause() instanceof java.net.SocketException
                                || e.getCause() instanceof java.io.IOException));
                    if (isNetworkError && attempt < 3) {
                        log.warn("[MainLoop] AI call failed (attempt {}/3): {} — retrying", attempt, e.getMessage());
                        asyncMessages.push("Connection error. Retrying... (attempt " + (attempt + 1) + "/3)");
                        try { Thread.sleep(3000); } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    } else {
                        workingSound.stop();
                        String errorReply = "AI error: " + e.getMessage();
                        transcriptService.save("BOT(error)", errorReply);
                        asyncMessages.push(errorReply);
                        return;
                    }
                }
            }
        } catch (Exception e) {
            workingSound.stop();
            log.error("[MainLoop] Unexpected error processing message: {}", e.getMessage(), e);
            asyncMessages.push("Error: " + e.getMessage());
        }
    }

    /**
     * Autonomous observation — captures screen, analyzes it, and acts on what's visible.
     */
    private void runAutonomousObservation() {
        Consumer<String> asyncCallback = result -> {
            transcriptService.save("BOT(observe)", result);
            asyncMessages.push(result);
        };
        fileTools.setAsyncCallback(asyncCallback);

        // Capture and analyze screen FIRST so the AI has context
        String systemMessage = systemCtx.buildSystemMessage();
        String screenAnalysis = null;
        try {
            screenAnalysis = screenStateService.captureAndAnalyze("observe screen for actionable tasks");
        } catch (Exception e) {
            log.warn("[MainLoop] Screen analysis failed: {}", e.getMessage());
        }

        if (screenAnalysis != null && !screenAnalysis.isBlank()) {
            systemMessage += "\n\nLIVE SCREEN ANALYSIS (what is currently on screen):\n"
                    + screenAnalysis + "\n";
        }

        String prompt = "AUTONOMOUS OBSERVATION — look at the LIVE SCREEN ANALYSIS above. "
                + "IGNORE all chat history. Based on what is CURRENTLY on screen: "
                + "1. Is there a form to fill? Fill it using TAB between fields. "
                + "2. Are there instructions to follow? Follow them step by step. "
                + "3. Is there a task in progress? Continue it. "
                + "4. Is there a button to click, a field to complete, or any actionable UI element? Act on it. "
                + "If the screen shows a normal desktop/browser with NO specific task, respond with just: IDLE";

        workingSound.start();

        try {
            String reply = chatClient.prompt()
                    .system(systemMessage)
                    .user(prompt)
                    .tools(toolRouter.selectTools(prompt))
                    .call()
                    .content();

            workingSound.stop();

            if (reply != null && !reply.isBlank() && !reply.trim().equalsIgnoreCase("IDLE")) {
                transcriptService.save("BOT(observe)", reply);
                asyncMessages.push(reply);
                lastActivityTime = System.currentTimeMillis();
            } else {
                // Nothing to do — wait longer before next check (30s idle)
                lastActivityTime = System.currentTimeMillis() + 25000;
            }
        } catch (Exception e) {
            workingSound.stop();
            log.warn("[MainLoop] Autonomous observation failed: {}", e.getMessage());
            lastActivityTime = System.currentTimeMillis();
        }
    }

    private void seedChatMemory() {
        if (chatMemory == null) {
            log.info("[ChatService] ChatMemory not available — skipping history seed.");
            return;
        }
        var history = transcriptService.getStructuredHistory();
        if (history.isEmpty()) {
            log.info("[ChatService] No chat history to seed into ChatMemory.");
            return;
        }
        java.util.List<Message> messages = new java.util.ArrayList<>();
        for (var entry : history) {
            String text = (String) entry.get("text");
            boolean isUser = (Boolean) entry.get("isUser");
            if (isUser) {
                messages.add(new UserMessage(text));
            } else {
                messages.add(new AssistantMessage(text));
            }
        }
        chatMemory.add("mins-bot-local", messages);
        log.info("[ChatService] Seeded ChatMemory with {} messages from transcript history.", messages.size());
    }

    /**
     * Clear Spring AI ChatMemory and transcript so the AI starts fresh.
     * Called when user clicks "Clear chat" in the UI.
     */
    public void clearChatMemory() {
        if (chatMemory != null) {
            try {
                chatMemory.clear("mins-bot-local");
                log.info("[ChatService] ChatMemory cleared.");
            } catch (Exception e) {
                log.warn("[ChatService] Failed to clear ChatMemory: {}", e.getMessage());
            }
        }
        transcriptService.clearHistory();
        log.info("[ChatService] Chat history and AI memory cleared.");
    }

    /** Returns and removes the next async result, or null if none. */
    public String pollAsyncResult() {
        return asyncMessages.poll();
    }

    /** Returns and removes all pending tool execution status messages. */
    public java.util.List<String> drainToolStatus() {
        return toolNotifier.drain();
    }

    // ═══ Task planning ═══
    @Value("${app.planning.enabled:true}")
    private boolean planningEnabled;

    /** When false, skip screenshot+vision before each chat reply (faster; bot can still takeScreenshot when needed). */
    @Value("${app.chat.live-screen-on-message:true}")
    private boolean liveScreenOnMessage;

    // ═══ Autonomous mode ═══
    @Value("${app.autonomous.enabled:false}")
    private boolean autonomousEnabled;
    @Value("${app.autonomous.idle-timeout-seconds:60}")
    private int autonomousIdleTimeoutSeconds;
    @Value("${app.autonomous.pause-between-steps-ms:30000}")
    private int autonomousPauseBetweenStepsMs;

    private volatile long lastActivityTime = System.currentTimeMillis();
    private volatile boolean autonomousRunning = false;
    private volatile java.awt.Point lastCheckedMousePos = null;
    /** Last message pushed from autonomous mode; cleared on user input so we don't repeat when idle. */
    private volatile String lastAutonomousMessageSent = null;
    /** True after autonomous concluded "all directives addressed"; cleared on user input. */
    private volatile boolean autonomousConcludedAllAddressed = false;

    // ═══ Main agent loop ═══
    /** Runs planning LLM and screen capture in parallel so prep does not run strictly back-to-back. */
    private final ExecutorService chatPrepExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "chat-prep");
        t.setDaemon(true);
        return t;
    });

    /** Queue of user messages to be processed by the main loop thread. */
    private final ConcurrentLinkedQueue<String> userMessageQueue = new ConcurrentLinkedQueue<>();
    /** True while the main loop is processing an AI call. */
    private volatile boolean mainLoopBusy = false;
    private volatile Thread mainLoopThread = null;

    /** Set by the stop button — the main loop checks this to abort the current AI call. */
    private volatile boolean stopRequested = false;

    /**
     * Request the bot to stop whatever it's currently doing.
     * Interrupts the main loop thread, stops TTS and working sound, pushes a "Stopped." message.
     */
    public void requestStop() {
        if (!mainLoopBusy) return;
        stopRequested = true;
        ttsTools.stopPlayback();
        workingSound.stop();
        Thread t = mainLoopThread;
        if (t != null) t.interrupt();
        log.info("[ChatService] Stop requested by user");
    }

    /** True if the main loop is currently processing. */
    public boolean isBusy() { return mainLoopBusy; }

    // Audio transcription properties (still uses raw HTTP)
    @Value("${app.openai.api-key:}")
    private String openAiApiKey;
    @Value("${app.openai.base-url:https://api.openai.com/v1}")
    private String openAiBaseUrl;
    @Value("${app.openai.transcription-model:gpt-4o-mini-transcribe}")
    private String openAiTranscriptionModel;

    @Value("${server.port:8765}")
    private int serverPort;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .version(Version.HTTP_1_1)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Get bot reply for a given message. Used by the in-app chat and all platform integrations.
     *
     * Flow:
     * 1. If Spring AI ChatClient is available → tool-calling path
     * 2. If unavailable → regex fallback via PcAgentService.tryExecute()
     * 3. If nothing matched → placeholder reply
     */
    public String getReply(String message) {
        if (message == null) {
            message = "";
        }

        String trimmed = message.trim();
        lastActivityTime = System.currentTimeMillis();
        lastAutonomousMessageSent = null;
        autonomousConcludedAllAddressed = false;
        toolNotifier.clear();
        transcriptService.save("USER", trimmed);

        // Cancel any pending 30-second quit when user sends a new message
        quitService.cancelPendingQuit();

        // Stop any currently playing TTS audio so the bot can respond to new input
        ttsTools.stopPlayback();

        // Quit command: reply synchronously
        if (isQuitCommand(trimmed)) {
            transcriptService.save("BOT", QUIT_REPLY);
            quitService.scheduleQuitIn30Sec();
            return QUIT_REPLY;
        }

        // Open command center: reply synchronously
        if (isOpenCommandCenter(trimmed)) {
            String url = "http://localhost:" + serverPort;
            try {
                String os = System.getProperty("os.name", "").toLowerCase();
                if (os.contains("win")) {
                    String ps = String.format(
                            "Add-Type @'\n" +
                            "using System; using System.Runtime.InteropServices;\n" +
                            "public class W { [DllImport(\"user32.dll\")] public static extern bool SetForegroundWindow(IntPtr h); " +
                            "[DllImport(\"user32.dll\")] public static extern bool ShowWindow(IntPtr h, int c); }\n" +
                            "'@\n" +
                            "$found=$false; " +
                            "Get-Process chrome -ErrorAction SilentlyContinue | ForEach-Object { " +
                            "  if ($_.MainWindowTitle -match 'Mins Bot' -or $_.MainWindowTitle -match 'localhost:%d') { " +
                            "    [W]::ShowWindow($_.MainWindowHandle, 9); " +
                            "    [W]::SetForegroundWindow($_.MainWindowHandle); " +
                            "    $found=$true; return } }; " +
                            "if (-not $found) { Start-Process chrome '%s' }",
                            serverPort, url);
                    new ProcessBuilder("powershell", "-NoProfile", "-Command", ps).start();
                } else if (os.contains("mac")) {
                    new ProcessBuilder("open", "-a", "Google Chrome", url).start();
                } else {
                    new ProcessBuilder("google-chrome", url).start();
                }
                String reply = "Opening command center in Chrome...";
                transcriptService.save("BOT", reply);
                return reply;
            } catch (Exception e) {
                log.warn("[ChatService] Failed to open Chrome, trying default browser: {}", e.getMessage());
                try {
                    java.awt.Desktop.getDesktop().browse(URI.create(url));
                    String reply = "Opening command center in browser...";
                    transcriptService.save("BOT", reply);
                    return reply;
                } catch (Exception ex) {
                    String reply = "Could not open browser: " + ex.getMessage();
                    transcriptService.save("BOT", reply);
                    return reply;
                }
            }
        }

        // No AI configured — regex fallback (sync)
        if (chatClient == null) {
            Consumer<String> asyncCallback = result -> {
                transcriptService.save("BOT(agent)", result);
                asyncMessages.push(result);
            };
            String agentReply = pcAgent.tryExecute(trimmed, asyncCallback);
            if (agentReply != null) {
                transcriptService.save("BOT", agentReply);
                return agentReply;
            }
            String noAiReply = "AI not connected. Set your API key in application-secrets.properties and restart.";
            transcriptService.save("BOT", noAiReply);
            return noAiReply;
        }

        // ═══ Queue message for the main loop thread ═══
        userMessageQueue.add(trimmed);
        log.info("[ChatService] Queued user message for main loop: {}",
                trimmed.substring(0, Math.min(trimmed.length(), 80)));

        // Return empty — the main loop will push the actual response via asyncMessages
        return "";
    }

    /** Auto-speak the reply if TTS auto_speak is enabled. Non-blocking (runs on background thread).
     *  Long replies (>50 chars) are summarized first so TTS doesn't read a wall of text. */
    private void autoSpeak(String reply) {
        if (!ttsTools.isAutoSpeak() || reply == null || reply.isBlank()) return;

        if (reply.length() > 50 && chatClient != null) {
            // Summarize long replies before speaking — runs in background to avoid blocking
            Thread t = new Thread(() -> {
                try {
                    String summary = chatClient.prompt()
                            .user("Summarize this chatbot response in 1-2 short sentences for text-to-speech. "
                                    + "Be concise and conversational. Only output the summary, nothing else:\n\n" + reply)
                            .call()
                            .content();
                    if (summary != null && !summary.isBlank()) {
                        ttsTools.speakAsync(summary);
                    }
                } catch (Exception e) {
                    log.debug("[TTS] Summarization failed, speaking truncated: {}", e.getMessage());
                    ttsTools.speakAsync(reply.substring(0, Math.min(reply.length(), 100)));
                }
            }, "tts-summarize");
            t.setDaemon(true);
            t.start();
        } else {
            ttsTools.speakAsync(reply);
        }
    }

    /** True if the user message is a request to open the command center in Chrome. */
    private static boolean isOpenCommandCenter(String trimmed) {
        if (trimmed == null || trimmed.isEmpty()) return false;
        String lower = trimmed.toLowerCase();
        return lower.equals("open command center") || lower.equals("command center")
                || lower.equals("open command centre") || lower.equals("command centre");
    }

    /** True if the user message is a quit request (e.g. "quit", "exit", "close mins bot"). */
    private static boolean isQuitCommand(String trimmed) {
        if (trimmed == null || trimmed.isEmpty()) return false;
        String lower = trimmed.toLowerCase();
        return lower.equals("quit") || lower.equals("exit")
                || lower.equals("close") || lower.equals("close bot")
                || lower.equals("close mins bot") || lower.equals("exit mins bot");
    }

    private static String prepAcknowledgement(boolean willPlan, boolean willScreen) {
        if (willPlan && willScreen) {
            return "One moment — drafting a quick plan and capturing your screen…";
        }
        if (willPlan) {
            return "One moment — let me check…";
        }
        return "One moment — capturing your screen…";
    }

    /**
     * Planning-only LLM call; pushes plan to UI and todolist when non-SKIP. Runs on chat-prep pool.
     */
    private String executePlanningForMessage(String trimmed) {
        try {
            String plan = chatClient.prompt()
                    .system(PLANNING_PROMPT)
                    .user(trimmed)
                    .call()
                    .content();
            if (plan == null || plan.isBlank() || plan.strip().equalsIgnoreCase("SKIP")) {
                return null;
            }
            String generatedPlan = plan.strip();
            asyncMessages.push(generatedPlan);
            transcriptService.save("BOT(plan)", generatedPlan);

            try {
                java.nio.file.Path todoPath = java.nio.file.Paths.get(
                        System.getProperty("user.home"), "mins_bot_data", "todolist.txt");
                String timestamp = java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                String todoEntry = "\n--- Task: \"" + trimmed.substring(0, Math.min(trimmed.length(), 80))
                        + "\" | " + timestamp + " ---\n"
                        + generatedPlan.replace("⬜", "[PENDING]") + "\n";
                java.nio.file.Files.writeString(todoPath, todoEntry,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception e) {
                log.debug("[MainLoop] Could not save plan to todolist.txt: {}", e.getMessage());
            }
            return generatedPlan;
        } catch (Exception e) {
            log.debug("[MainLoop] Planning call failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Background-agent planner: isolated {@link ChatMemory} id, no async UI or todolist.
     *
     * @return trimmed plan text, or {@code null} for SKIP / empty / error
     */
    public String generateBackgroundAgentPlan(String mission, String agentJobId) {
        if (chatClient == null || mission == null || mission.isBlank()) {
            return null;
        }
        try {
            String cid = "agent-plan-" + (agentJobId != null && !agentJobId.isBlank() ? agentJobId : "x");
            String plan = chatClient.prompt()
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, cid))
                    .system(AGENT_PLANNING_PROMPT)
                    .user(mission.trim())
                    .call()
                    .content();
            if (plan == null || plan.isBlank()) {
                return null;
            }
            String s = plan.strip();
            if (s.equalsIgnoreCase("SKIP")) {
                return null;
            }
            return s;
        } catch (Exception e) {
            log.debug("[AgentPlan] Planning call failed: {}", e.getMessage());
            return null;
        }
    }

    /** Returns true if the message warrants a planning pre-step. Skips greetings and light Q&A. */
    private boolean needsPlanning(String message) {
        if (message == null || message.isBlank()) return false;
        int wordCount = message.trim().split("\\s+").length;
        if (wordCount <= 2) return false;
        if (isSimpleConversationalQuery(message)) return false;
        if (SystemContextProvider.isMessageAboutMinsbotSelfConfig(message)) return false;
        return true;
    }

    /**
     * Live screen before reply — only when the user likely needs desktop/browser context
     * or the request is long enough that extra context may help.
     */
    private boolean needsLiveScreenForMessage(String message) {
        if (message == null || message.isBlank()) return false;
        if (isSimpleConversationalQuery(message)) return false;
        if (SystemContextProvider.isMessageAboutMinsbotSelfConfig(message)) return false;
        int wordCount = message.trim().split("\\s+").length;
        if (wordCount <= 2) return false;
        if (wordCount >= 16) return true;
        return SCREEN_OR_AUTOMATION_HINT.matcher(message).find();
    }

    private static boolean isSimpleConversationalQuery(String message) {
        String t = message.trim();
        return SIMPLE_CONVERSATIONAL.matcher(t).find();
    }

    /** Returns true if the message is a resume/continue command. */
    private boolean isResumeCommand(String message) {
        if (message == null || message.isBlank()) return false;
        String lower = message.trim().toLowerCase();
        return lower.equals("continue") || lower.equals("go on") || lower.equals("keep going")
                || lower.equals("resume") || lower.equals("next") || lower.equals("proceed")
                || lower.startsWith("continue ") || lower.startsWith("go on ")
                || lower.startsWith("keep going") || lower.startsWith("resume ");
    }

    /**
     * Direct reply with a custom system prompt — used by multi-agent chat.
     * Skips planning, screen capture, and tool calling for fast responses.
     */
    public String getDirectReply(String userMessage, String systemPrompt) {
        if (chatClient == null) return "(AI not configured)";
        try {
            return chatClient.prompt()
                    .system(systemPrompt)
                    .user(userMessage)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("[MultiAgent] Direct reply failed: {}", e.getMessage());
            return "(error: " + e.getMessage() + ")";
        }
    }

    /** Native audio pipeline: WAV -> transcription -> text response. */
    public String getReplyFromAudio(byte[] wavAudio) {
        if (wavAudio == null || wavAudio.length == 0) {
            return "No audio captured.";
        }
        if (openAiApiKey == null || openAiApiKey.isBlank()) {
            return "Audio chat is not configured. Set spring.ai.openai.api-key.";
        }

        try {
            System.out.println("[AudioLLM] Sending transcription request. wavBytes=" + wavAudio.length
                    + ", transcriptionModel=" + openAiTranscriptionModel
                    + ", baseUrl=" + openAiBaseUrl);
            String transcript = transcribeAudio(wavAudio);
            if (transcript == null || transcript.isBlank()) {
                return "No speech detected.";
            }

            System.out.println("[AudioLLM] Transcript: " + transcript);
            transcriptService.save("USER(voice)", transcript);
            String reply = getReply(transcript);
            Map<String, String> result = Map.of(
                    "transcript", transcript,
                    "reply", reply == null ? "" : reply
            );
            return AUDIO_RESULT_PREFIX + objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            System.err.println("[AudioLLM] Exception during audio request: " + e.getMessage());
            e.printStackTrace();
            return "Audio chat error: " + e.getMessage();
        }
    }

    public String transcribeAudio(byte[] wavAudio) throws Exception {
        String boundary = "----MinsBotBoundary" + UUID.randomUUID();
        byte[] body = buildMultipartWavBody(boundary, wavAudio, openAiTranscriptionModel);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(openAiBaseUrl + "/audio/transcriptions"))
                .header("Authorization", "Bearer " + openAiApiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofSeconds(90))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<String> response = sendWithRetry(request, 3);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            System.err.println("[AudioLLM] Transcription failed. status=" + response.statusCode());
            System.err.println("[AudioLLM] Response body: " + response.body());
            throw new IllegalStateException("Transcription failed (" + response.statusCode() + ")");
        }

        JsonNode root = objectMapper.readTree(response.body());
        return root.path("text").asText("");
    }

    private byte[] buildMultipartWavBody(String boundary, byte[] wavAudio, String model) throws Exception {
        String crlf = "\r\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        out.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"model\"" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(model.getBytes(StandardCharsets.UTF_8));
        out.write(crlf.getBytes(StandardCharsets.UTF_8));

        out.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"voice.wav\"" + crlf)
                .getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: audio/wav" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(wavAudio);
        out.write(crlf.getBytes(StandardCharsets.UTF_8));

        out.write(("--" + boundary + "--" + crlf).getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private HttpResponse<String> sendWithRetry(HttpRequest request, int maxAttempts) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException ioe) {
                last = ioe;
                System.err.println("[AudioLLM] Network error on attempt " + attempt + "/" + maxAttempts + ": " + ioe.getMessage());
                if (attempt == maxAttempts) break;
                Thread.sleep(300L * attempt);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            }
        }
        throw last == null ? new IOException("Request failed after retries.") : last;
    }

    // ═══ Autonomous directive worker ═══

    /** Returns true if the system-wide mouse cursor has NOT moved since the last check. */
    private boolean isMouseStill() {
        try {
            java.awt.Point current = java.awt.MouseInfo.getPointerInfo().getLocation();
            if (lastCheckedMousePos != null && !current.equals(lastCheckedMousePos)) {
                lastCheckedMousePos = current;
                return false; // mouse moved
            }
            lastCheckedMousePos = current;
            return true;
        } catch (Exception e) {
            return true; // can't check (headless?), assume still
        }
    }

    /** Returns true if the user appears fully idle (no chat AND no mouse movement). */
    private boolean isUserIdle() {
        if (!isMouseStill()) return false;
        long chatIdleMs = System.currentTimeMillis() - lastActivityTime;
        return chatIdleMs >= autonomousIdleTimeoutSeconds * 1000L;
    }

    @Scheduled(fixedDelayString = "${app.autonomous.check-interval-ms:15000}")
    public void checkAutonomousWork() {
        if (!autonomousEnabled || chatClient == null || autonomousRunning || mainLoopBusy) return;

        String directives = DirectivesTools.loadDirectivesForPrompt();
        if (directives == null || directives.isBlank()) return;

        if (!isUserIdle()) return;
        if (autonomousConcludedAllAddressed) return; // already said "all directives addressed" with no new input

        autonomousRunning = true;
        int step = 0;
        try {
            log.info("[Autonomous] User idle — starting continuous directive work.");

            while (step < 100) {
                step++;

                String prompt = buildAutonomousPrompt(directives, step);

                Consumer<String> asyncCallback = result -> {
                    transcriptService.save("BOT(autonomous-agent)", result);
                    asyncMessages.push(result);
                };
                fileTools.setAsyncCallback(asyncCallback);
                workingSound.start();

                String reply = null;
                for (int attempt = 1; attempt <= 3; attempt++) {
                    try {
                        reply = chatClient.prompt()
                                .system(systemCtx.buildSystemMessage())
                                .user(prompt)
                                .tools(toolRouter.selectToolsForAutonomous(prompt))
                                .call()
                                .content();
                        break; // success
                    } catch (Exception retryEx) {
                        boolean isNetworkError = retryEx instanceof org.springframework.web.client.ResourceAccessException
                                || (retryEx.getCause() != null && (retryEx.getCause() instanceof java.net.SocketException
                                    || retryEx.getCause() instanceof java.io.IOException));
                        if (isNetworkError && attempt < 3) {
                            log.warn("[Autonomous] AI call failed (attempt {}/3): {} — retrying in 3s",
                                    attempt, retryEx.getMessage());
                            asyncMessages.push("Connection error during autonomous work. Retrying... (attempt " + (attempt + 1) + "/3)");
                            Thread.sleep(3000);
                        } else {
                            throw retryEx; // non-network error or final attempt — propagate
                        }
                    }
                }

                workingSound.stop();

                if (reply != null && !reply.isBlank()) {
                    String normalized = reply.trim().replaceAll("\\s+", " ");
                    String lower = normalized.toLowerCase();
                    // Don't repeat the same message when there's been no new user input
                    if (normalized.equals(lastAutonomousMessageSent)) {
                        autonomousConcludedAllAddressed = true; // treat as concluded so we don't re-run next interval
                        log.info("[Autonomous] Same message as last time (no new input) — skipping send and stopping.");
                        break;
                    }
                    // Check for "done" signal from the AI
                    if (lower.contains("all directives addressed")) {
                        autonomousConcludedAllAddressed = true;
                        lastAutonomousMessageSent = normalized;
                        log.info("[Autonomous] AI signaled completion at step {}.", step);
                        break; // Don't push "all directives addressed" to chat — it's noise
                    }
                    // Filter out "nothing actionable" responses — these are analysis, not useful output
                    if (lower.contains("no specific actionable") || lower.contains("no actionable")
                            || lower.contains("nothing actionable") || lower.contains("primarily focused on identity")
                            || lower.contains("primarily focused on personal")
                            || lower.contains("no tasks requiring research")) {
                        autonomousConcludedAllAddressed = true;
                        lastAutonomousMessageSent = normalized;
                        log.info("[Autonomous] Non-actionable directives detected — stopping silently.");
                        break; // Don't push analysis messages to chat
                    }
                    transcriptService.save("BOT(autonomous)", reply);
                    asyncMessages.push(reply);
                    lastAutonomousMessageSent = normalized;
                    log.info("[Autonomous] Step {} done: {}", step,
                            reply.length() > 100 ? reply.substring(0, 100) + "..." : reply);
                }

                // Pause between steps, then check if user is still away
                Thread.sleep(autonomousPauseBetweenStepsMs);

                if (!isMouseStill()) {
                    log.info("[Autonomous] Mouse movement detected — stopping after {} steps.", step);
                    break;
                }
                long chatIdleMs = System.currentTimeMillis() - lastActivityTime;
                if (chatIdleMs < autonomousIdleTimeoutSeconds * 1000L) {
                    log.info("[Autonomous] User sent a message — stopping after {} steps.", step);
                    break;
                }
            }

            log.info("[Autonomous] Session ended after {} steps.", step);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("[Autonomous] Interrupted after {} steps.", step);
        } catch (Exception e) {
            log.error("[Autonomous] Error at step {}: {}", step, e.getMessage(), e);
        } finally {
            workingSound.stop();
            autonomousRunning = false;
            lastActivityTime = System.currentTimeMillis(); // prevent immediate restart
        }
    }

    private String buildAutonomousPrompt(String directives, int step) {
        if (step == 1) {
            return """
                    You are now in AUTONOMOUS MODE. The user is away from the computer.
                    Review the directives below and plan how to accomplish them.
                    Think step by step: what information do you need? What research should you do?

                    For each actionable directive, gather relevant information using the tools available.

                    WEB RESEARCH — use the right tool for the job:

                    TEXT SEARCH (for research, facts, prices, flights, hotels, reviews, etc.):
                    - searchWeb(query) — search the web and return text results (titles, snippets, links). \
                    USE THIS for any research task: flights, hotels, prices, facts, news, comparisons.
                    - readWebPage(url) — fetch a specific page and return readable text content.

                    IMAGE DOWNLOAD (ONLY when user wants to collect image files):
                    - browseSearchAndDownloadImages(query, directiveName, maxImages) — download image files \
                    using a real browser.
                    - searchAndDownloadImages(query, directiveName, maxImages) — download image files via HTTP.

                    HEADLESS BROWSING (for JS-heavy sites, forms, clicking):
                    - browsePage(url) — navigate with a real Chromium browser and get rendered text.
                    - browseAndGetImages(url) — extract all image URLs after JS renders.
                    - browseAndGetLinks(url) — extract all links after JS renders.
                    - screenshotPage(url, directiveName) — take a full-page screenshot and save it.
                    - browseAndClick(url, selector) — click an element on a page.
                    - browseAndFill(url, selector, value, submit) — fill a form and optionally submit.

                    HTTP FETCH (lighter, faster, for simple/static pages):
                    - fetchPageText(url) — simple HTTP fetch and strip HTML.
                    - extractImageUrls(url), extractLinks(url) — regex-based extraction.
                    - downloadFileToFolder(url, filename, directiveName) — download any file from a URL.
                    - fetchPageWithImages(url) — get both text and image URLs.

                    IMPORTANT: When the user asks to "research", "look up", "find information about", \
                    "search for flights/hotels/prices" — use searchWeb(), NOT image download tools.
                    Do NOT open the system browser. These tools work silently in the background.

                    SAVING DATA:
                    - Do NOT write to directives.txt. That file is ONLY for directive definitions.
                    - Use saveDirectiveFinding(directiveName, content) to save text findings into a \
                    per-directive folder: ~/mins_bot_data/directive_{name}/
                    - Use saveDirectiveScreenshot(directiveName) to capture the current screen as an image.
                    - Use searchAndDownloadImages, downloadFile (to path), or downloadFileToFolder (to directive folder) to save images from the web.
                    - Use a short, descriptive directiveName derived from the directive \
                    (e.g. "search-condo-new-york", "make-money-online").
                    - Use listDirectiveData(directiveName) to see what you've gathered so far.
                    - Each directive gets its own folder. All gathered data (text, images) goes there.

                    Report what you did and what you plan to do next. Be concise.
                    CRITICAL: If the directives are ONLY personality/tone/identity settings (like names, \
                    preferences, family info) with NO research tasks, NO data gathering, and NO actionable \
                    work to do — respond with EXACTLY "All directives addressed" and nothing else. \
                    Do NOT analyze or describe the directives. Do NOT say "no specific actionable tasks". \
                    Just say "All directives addressed" so the system stops cleanly.

                    DIRECTIVES:
                    """ + directives;
        }
        return """
                [Autonomous step %d] You are still in AUTONOMOUS MODE. The user is still away.

                Continue working on the directives. Use listDirectiveData to review \
                what you've already gathered, then decide what the NEXT concrete step is.

                Keep researching, gathering information, and downloading files. \
                Ask yourself: "Based on what I know about this user, what is the best way to accomplish these directives?" \
                Then act on it.

                REMINDER — web research tools (headless, no visible browser):
                Text search: searchWeb(query), readWebPage(url) — USE THESE for research tasks.
                Headless browser: browsePage, browseAndGetLinks, browseAndClick, browseAndFill.
                Image downloads ONLY: browseSearchAndDownloadImages, searchAndDownloadImages.
                IMPORTANT: For research (flights, hotels, prices, facts) use searchWeb, NOT image tools.

                SAVING DATA:
                - Text: saveDirectiveFinding(directiveName, content)
                - Images: searchAndDownloadImages or downloadFileToFolder with directiveName
                - Screenshots: saveDirectiveScreenshot(directiveName)
                - Do NOT write to directives.txt — that file is only for directive definitions.

                Report briefly what you did this step. Be concise.
                If you've completed all actionable directives or there's nothing more to do, \
                say "All directives addressed" so the system knows to stop.

                DIRECTIVES:
                """.formatted(step) + directives;
    }

}
