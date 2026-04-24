package com.minsbot;

import com.minsbot.agent.AsyncMessageService;
import com.minsbot.agent.ModuleStatsService;
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
     * Simple on/off toggle commands — no planning needed, no screen capture needed.
     * These are direct tool calls (toggle mode, enable/disable feature, mute/unmute, etc.).
     */
    private static final Pattern SIMPLE_TOGGLE_COMMAND = Pattern.compile(
            "(?i)^(stop|start|enable|disable|turn\\s+(on|off)|activate|deactivate|pause|resume)\\s+"
                    + "(watching|watch\\s+mode|listening|listen\\s+mode|vocal|voice|tts|speaking|audio|"
                    + "mouth|ears?|eyes?|screen\\s+watch|audio\\s+listen|wake\\s+word|hey\\s+jarvis|"
                    + "auto[- ]?pilot|autopilot|proactive|control|keyboard|mic|microphone)"
                    + "(\\s+(mode|now|please))?[.!?\\s]*$"
                    + "|^(stop|start|enable|disable|turn\\s+(on|off))\\s+(watching|listening|speaking)\\s+my\\s+\\w+[.!?\\s]*$"
                    + "|^(mute|unmute|louder|quieter)[.!?\\s]*$");

    /**
     * Direct file-system operations — single tool call, no planning or screen capture.
     * Covers "open X", "count files in Y", "list files in Z", "find *.pdf in W", etc.
     */
    private static final Pattern SIMPLE_FILE_OPERATION = Pattern.compile(
            "(?i)^(can\\s+you\\s+)?(please\\s+)?"
                    + "(open|count|list|show|find|search(?:\\s+for)?)\\s+"
                    + ".{0,120}?"
                    + "\\b(desktop|downloads?|documents?|pictures?|videos?|music|onedrive|folder|directory|file|files|pdf|pdfs|docx?|xlsx?|pptx?|txt|md|mp3|mp4|jpg|jpeg|png)\\b"
                    + ".{0,120}?[.!?]?\\s*$");

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

            CODE GENERATION — ALWAYS SKIP for any request that creates, modifies, rewrites, ports, converts, \
            migrates, refactors, continues, loads, or runs a codebase/project. Those route to ClaudeCodeTools \
            / ContinueProjectTools which do the whole job in a single delegated call. A 10-step shell plan \
            picks wrong stacks, hallucinates progress, and falsely marks steps done. Respond with SKIP for:
            - "create me a <lang> project / app / codebase / spring boot / react / python ..."
            - "scaffold / generate / build me a ..."
            - "rewrite it using <stack>" / "convert <name> to <stack>" / "port <name> to <lang>"
            - "migrate / reimplement / redo <name> in ..."
            - "continue / resume / work on <name>" / "add <feature> to <name>" / "modify <name>"
            - "load / open / run / test / start <name> project"
            - "list my projects" / "where is my <name>"
            Even if the user specifies the folder, port, or stack — STILL SKIP. Those details ride along in \
            the single tool call.

            CONNECTED SERVICES — NEVER plan manual browser logins for these; API tools are wired up:
            - Gmail is connected via OAuth → use getUnreadEmails / searchEmails / getEmailDetails / sendEmail.
              DO NOT plan "open browser → go to mail.google.com → enter password" — that's wrong.
            - Google Calendar is connected → use getTodayEvents / getUpcomingEvents / getEventsForDate.
            - Google Drive is connected → use the drive tools, not a browser.
            - YouTube Data API is connected → use getMyYouTubeChannel / searchYouTubeVideos / etc.
            - Spotify Web API (if connected) → use spotifyPlay for music.
            - For ANY "check my email", "what's on my calendar", "find file in drive", "my YouTube stats" style
              request: respond with SKIP — the agent will call the right API tool directly in one shot.

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

            User: "how much is this app in $?"
            SKIP

            User: "how much does gpt-4 cost?"
            SKIP

            User: "what's the price of notion?"
            SKIP

            User: "is obsidian free?"
            SKIP

            User: "who is the ceo of anthropic?"
            SKIP

            User: "when was react 19 released?"
            SKIP

            User: "open claude architect pdf in my desktop"
            SKIP

            User: "how many files in my downloads folder"
            SKIP

            User: "find all PDFs on my desktop"
            SKIP

            User: "list files in my documents"
            SKIP

            User: "check my email"
            SKIP

            User: "check my choloville email"
            SKIP

            User: "any new emails?"
            SKIP

            User: "what's on my calendar today"
            SKIP

            User: "show my YouTube subscribers"
            SKIP

            User: "find the quarterly report in my drive"
            SKIP

            User: "create me a java project for foo"
            SKIP

            User: "scaffold a spring boot app called bar with tailwind"
            SKIP

            User: "rewrite it using java, tailwind css"
            SKIP

            User: "convert rich-app to java17 springboot tailwind css, and run it localhost:8080"
            SKIP

            User: "continue rich-app project"
            SKIP

            User: "load rich-app and run it"
            SKIP

            User: "add a login page to house-shopping"
            SKIP

            User: "where is my rich-app project"
            SKIP

            User: "generate me an app"
            SKIP

            User: "make me a spring boot app called hello"
            SKIP

            User: "build me a react app"
            SKIP

            User: "scaffold a fastapi project and run it"
            SKIP

            User: "double check https://arxiv.org/, give me list of app ideas"
            SKIP

            User: "browse hackernews and summarize the top stories"
            SKIP

            User: "check this website and tell me what it's about: <url>"
            SKIP

            User: "go to techcrunch, find trending AI startups"
            SKIP

            User: "research <topic> and give me a summary"
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

    @org.springframework.beans.factory.annotation.Autowired
    private com.minsbot.agent.AgentLoopService agentLoopService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.minsbot.cost.TokenUsageService tokenUsageService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.minsbot.approval.ToolPermissionService approvalService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.minsbot.offline.OfflineModeService offlineModeService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.minsbot.agent.tools.LocalModelTools localModelTools;
    private final TtsTools ttsTools;
    private final ScreenStateService screenStateService;
    private final com.minsbot.agent.tools.ScreenWatchingTools screenWatchingTools;
    private final com.minsbot.agent.tools.ClipboardHistoryTools clipboardHistoryTools;
    private final com.minsbot.agent.AutoMemoryExtractor autoMemoryExtractor;

    @Autowired(required = false)
    private ModuleStatsService moduleStats;

    @Value("${spring.ai.openai.chat.options.model:unknown}")
    private String chatModelName;

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

    /** Short-circuits the main-loop queue for stateless messages (greetings, math, port kills, log tweaks). */
    @org.springframework.beans.factory.annotation.Autowired
    private FastLaneService fastLane;

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
                       com.minsbot.agent.tools.ScreenWatchingTools screenWatchingTools,
                       com.minsbot.agent.tools.ClipboardHistoryTools clipboardHistoryTools,
                       com.minsbot.agent.AutoMemoryExtractor autoMemoryExtractor) {
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
        this.clipboardHistoryTools = clipboardHistoryTools;
        this.autoMemoryExtractor = autoMemoryExtractor;
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
                        // ── Poll clipboard history ──
                        try { clipboardHistoryTools.poll(); } catch (Exception ignored) {}

                        // ── No user message: observe screen autonomously ──
                        // Only run if watch mode (eye icon) is enabled AND idle long enough
                        long idleMs = System.currentTimeMillis() - lastActivityTime;
                        if (screenWatchingTools.isWatching() && idleMs >= 5000 && !mainLoopBusy) {
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

        // Auto-extract life facts from user message (async, never blocks chat)
        autoMemoryExtractor.analyzeAsync(trimmed);

        // SSE on transcript.save pushes to the UI; don't also enqueue on asyncMessages
        // or the user sees each reply rendered twice.
        Consumer<String> asyncCallback = result -> transcriptService.save("BOT(agent)", result);

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
                    // Offline mode gate: the active chatClient here is the configured
                    // provider (OpenAI / Anthropic / Gemini if a cloud key is set, or Ollama
                    // when the user has swapped via ModelSwitchTools). Spring AI routes
                    // cloud calls to api.openai.com / api.anthropic.com etc. — those need
                    // to be blocked when the user has flipped the offline shield.
                    if (offlineModeService != null && offlineModeService.isOffline()) {
                        // Prefer the runtime-swapped provider over the boot-time config:
                        // `chatModelName` is bound from application.properties at startup and
                        // doesn't update when tools swap the ChatClient to Ollama.
                        boolean activeIsCloud;
                        if (localModelTools != null
                                && "ollama".equalsIgnoreCase(localModelTools.getActiveProviderName())) {
                            activeIsCloud = false;
                        } else {
                            activeIsCloud = isCloudModel(chatModelName);
                        }
                        if (activeIsCloud) {
                            // Try to auto-switch first — user's "offline on" intent means
                            // "use whatever local model I have" rather than "refuse to reply".
                            String picked = null;
                            if (localModelTools != null) {
                                try { picked = localModelTools.autoSwitchToBestLocal(); }
                                catch (Exception ignored) {}
                            }
                            if (picked == null) {
                                String msg = "🛡️ Offline mode is ON and the active chat model is `"
                                        + chatModelName + "` (cloud), but no local Ollama model is "
                                        + "installed to fall back to. Install one in the Models tab "
                                        + "(e.g. llama3.2:3b) or toggle offline mode off.";
                                workingSound.stop();
                                transcriptService.save("BOT", msg);
                                return;
                            }
                            asyncMessages.push("🛡️ Offline mode ON — switched chat to local `" + picked + "`.");
                        }
                    }

                    // Cost-budget gate: honors user-chosen cap mode from the Costs tab.
                    //   warn     → no gate, alerts already fired in TokenUsageService.record()
                    //   throttle → auto-swap to a local Ollama model (free) for the rest of today
                    //   hardcap  → refuse cloud chat with a message until tomorrow / budget raised
                    if (tokenUsageService != null && tokenUsageService.isOverBudget()) {
                        boolean activeIsCloudNow = !(localModelTools != null
                                && "ollama".equalsIgnoreCase(localModelTools.getActiveProviderName()));
                        if (activeIsCloudNow) {
                            String mode = tokenUsageService.getCapMode();
                            if ("hardcap".equals(mode)) {
                                String msg = String.format(
                                        "🚫 Daily cost cap hit — $%.2f spent ≥ $%.2f budget. Cloud chat refused. "
                                                + "Raise the budget in the Costs tab, switch to Throttle mode to auto-use "
                                                + "local Ollama, or wait until tomorrow.",
                                        tokenUsageService.spentToday(),
                                        tokenUsageService.getDailyBudgetUsd());
                                workingSound.stop();
                                transcriptService.save("BOT", msg);
                                return;
                            }
                            if ("throttle".equals(mode) && localModelTools != null) {
                                String picked = null;
                                try { picked = localModelTools.autoSwitchToBestLocal(); }
                                catch (Exception ignored) {}
                                if (picked != null) {
                                    asyncMessages.push("💸 Over daily budget — throttled to local `" + picked + "`.");
                                } else {
                                    String msg = String.format(
                                            "💸 Over daily budget ($%.2f / $%.2f) and Throttle mode is on, but no "
                                                    + "local model is installed to fall back to. Install one in Models, "
                                                    + "or switch cap mode to Warn/Hardcap.",
                                            tokenUsageService.spentToday(),
                                            tokenUsageService.getDailyBudgetUsd());
                                    workingSound.stop();
                                    transcriptService.save("BOT", msg);
                                    return;
                                }
                            }
                        }
                    }

                    // Agent-loop wrapper: the LLM can request additional turns by emitting
                    // `[[CONTINUE]]` at the end of its reply. AgentLoopService handles the
                    // marker stripping + concatenation. First turn uses the real user message;
                    // subsequent turns get a "continue" prompt so the model keeps working.
                    final String[] turnUserMsg = { trimmed };
                    String reply = agentLoopService.runUntilDone(() -> {
                        String um = turnUserMsg[0];
                        turnUserMsg[0] = "Continue the task. Use tools as needed. "
                                + "If still incomplete after this turn, end with [[CONTINUE]] again.";
                        // Small local Ollama models emit tool-call JSON as plain text instead
                        // of using the native tool_calls field — the result is raw
                        // {"name":"...","arguments":{...}} strings shown as chat replies.
                        // Skip tools on Ollama to keep chat coherent; users who want tool
                        // execution can switch to a cloud provider.
                        boolean onOllama = localModelTools != null
                                && "ollama".equalsIgnoreCase(localModelTools.getActiveProviderName());
                        var promptSpec = chatClient.prompt()
                                .system(finalSystemMessage)
                                .user(um);
                        if (!onOllama) {
                            promptSpec = promptSpec.tools(toolRouter.selectTools(trimmed));
                        }
                        org.springframework.ai.chat.model.ChatResponse resp = promptSpec
                                .call()
                                .chatResponse();
                        // Record every call for the Costs tab, even if usage metadata is absent.
                        // Some provider configs (Ollama, certain Azure gateways, proxies that strip
                        // fields) don't return usage — we still want the call count to reflect reality.
                        try {
                            if (tokenUsageService != null && resp != null) {
                                int pt = 0, ct = 0;
                                boolean haveUsage = false;
                                // Prefer the model id the provider itself reported — accurate across
                                // runtime model swaps via ModelSwitchTools. Fall back to the boot-time
                                // config value only when the provider doesn't populate it.
                                String effectiveModel = chatModelName;
                                if (resp.getMetadata() != null) {
                                    try {
                                        String reported = resp.getMetadata().getModel();
                                        if (reported != null && !reported.isBlank()) effectiveModel = reported;
                                    } catch (Throwable ignored) { /* some Spring AI versions don't expose getModel() */ }
                                    if (resp.getMetadata().getUsage() != null) {
                                        var u = resp.getMetadata().getUsage();
                                        if (u.getPromptTokens() != null) pt = u.getPromptTokens().intValue();
                                        if (u.getCompletionTokens() != null) ct = u.getCompletionTokens().intValue();
                                        haveUsage = (pt > 0 || ct > 0);
                                    }
                                }
                                tokenUsageService.record(effectiveModel, pt, ct);
                                if (!haveUsage) {
                                    log.warn("[Cost] Provider returned null/zero usage for model '{}'. "
                                            + "Call is logged with 0 tokens ($0). Check the provider's "
                                            + "API response or upgrade Spring AI if this is unexpected.",
                                            effectiveModel);
                                } else {
                                    log.debug("[Cost] recorded {} (pt={}, ct={})", effectiveModel, pt, ct);
                                }
                            }
                        } catch (Exception costEx) {
                            log.debug("[Cost] could not record usage: {}", costEx.getMessage());
                        }
                        return (resp != null && resp.getResult() != null
                                && resp.getResult().getOutput() != null)
                                ? resp.getResult().getOutput().getText() : "";
                    }, 10);

                    if (stopRequested) {
                        log.info("[MainLoop] Stop requested after AI call — discarding reply");
                        workingSound.stop();
                        asyncMessages.push("Stopped.");
                        return;
                    }

                    workingSound.stop();
                    if (moduleStats != null) moduleStats.recordChatCall(chatModelName);
                    // Safety net: the model occasionally echoes the extractor-style "NONE" token
                    // as its main reply. Intercept and return a helpful message instead of a blank.
                    if (reply != null && reply.trim().matches("(?i)^(none|n/?a|unknown|idk|i don'?t know\\.?)$")) {
                        log.warn("[MainLoop] Model returned lazy '{}' — substituting fallback", reply.trim());
                        reply = "I don't have that on hand yet. Tell me and I'll remember it for next time — "
                                + "you can also check the Memories tab to see what I've already saved.";
                    }
                    if (reply != null && !reply.isBlank()) {
                        reply = appendToolsFootnote(reply);
                        log.info("[MainLoop] Bot reply ready ({} chars). Dispatching via SSE + async queue fallback.", reply.length());
                        transcriptService.save("BOT", reply); // Primary: SSE.
                        // Fallback: also push to the async queue so /api/chat/async polling can
                        // deliver the reply if the SSE channel was dropped (Connection aborted
                        // errors from the WebView have been observed). The frontend dedupes via
                        // data-pending-match / data-hist-key so this does NOT double-render.
                        asyncMessages.push(reply);
                        autoSpeak(reply);
                    } else {
                        // Empty LLM reply (e.g., tool call was self-sufficient). If tools fired,
                        // still surface a line so the user sees what happened instead of silence.
                        java.util.List<String> used = toolNotifier.drainTurnLog();
                        if (!used.isEmpty()) {
                            String summary = "— used: " + String.join(" · ", used);
                            log.info("[MainLoop] Empty reply; surfacing tool-usage summary instead.");
                            transcriptService.save("BOT", summary);
                            asyncMessages.push(summary);
                        } else {
                            log.warn("[MainLoop] Empty reply and no tools fired — nothing to show user.");
                        }
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
                        transcriptService.save("BOT(error)", errorReply); // SSE delivers; no async push
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
        Consumer<String> asyncCallback = result -> transcriptService.save("BOT(observe)", result);
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
                transcriptService.save("BOT(observe)", reply); // SSE delivers; no async push
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
        String r = asyncMessages.poll();
        if (r != null) {
            log.info("[MainLoop] Async reply drained by /api/chat/async ({} chars).",
                    r.length());
        }
        return r;
    }

    /** Returns and removes all pending tool execution status messages. */
    public java.util.List<String> drainToolStatus() {
        return toolNotifier.drain();
    }

    /**
     * Drain the per-turn tool log and append a footnote line to the bot reply so
     * the user can see exactly which tools ran. Returns the reply unchanged when
     * no tools fired this turn.
     */
    private String appendToolsFootnote(String reply) {
        java.util.List<String> used = toolNotifier.drainTurnLog();
        if (used.isEmpty()) return reply;
        String footnote = "— used: " + String.join(" · ", used);
        return reply + "\n\n" + footnote;
    }

    // ═══ Task planning ═══
    @Value("${app.planning.enabled:true}")
    private boolean planningEnabled;

    /** When false, skip screenshot+vision before each chat reply (faster; bot can still takeScreenshot when needed). */
    @Value("${app.chat.live-screen-on-message:true}")
    private boolean liveScreenOnMessage;

    // ═══ Autonomous mode ═══
    @Value("${app.autonomous.enabled:false}")
    private volatile boolean autonomousEnabled;
    @Value("${app.autonomous.idle-timeout-seconds:60}")
    private volatile int autonomousIdleTimeoutSeconds;
    @Value("${app.autonomous.pause-between-steps-ms:30000}")
    private volatile int autonomousPauseBetweenStepsMs;

    private static final java.nio.file.Path AUTONOMOUS_SETTINGS_PATH =
            java.nio.file.Paths.get(System.getProperty("user.home"), "mins_bot_data", "autonomous_settings.txt");

    public boolean isAutonomousEnabled() { return autonomousEnabled; }
    public int getAutonomousIdleTimeoutSeconds() { return autonomousIdleTimeoutSeconds; }
    public int getAutonomousPauseBetweenStepsMs() { return autonomousPauseBetweenStepsMs; }

    public synchronized void setAutonomousEnabled(boolean v) {
        this.autonomousEnabled = v;
        saveAutonomousSettings();
    }
    public synchronized void setAutonomousIdleTimeoutSeconds(int v) {
        this.autonomousIdleTimeoutSeconds = Math.max(5, Math.min(3600, v));
        saveAutonomousSettings();
    }
    public synchronized void setAutonomousPauseBetweenStepsMs(int v) {
        this.autonomousPauseBetweenStepsMs = Math.max(1000, Math.min(600000, v));
        saveAutonomousSettings();
    }

    @jakarta.annotation.PostConstruct
    void loadAutonomousSettings() {
        try {
            if (!java.nio.file.Files.exists(AUTONOMOUS_SETTINGS_PATH)) return;
            for (String line : java.nio.file.Files.readAllLines(AUTONOMOUS_SETTINGS_PATH)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                switch (k) {
                    case "enabled" -> this.autonomousEnabled = Boolean.parseBoolean(v);
                    case "idleTimeoutSeconds" -> { try { this.autonomousIdleTimeoutSeconds = Integer.parseInt(v); } catch (NumberFormatException ignored) {} }
                    case "pauseBetweenStepsMs" -> { try { this.autonomousPauseBetweenStepsMs = Integer.parseInt(v); } catch (NumberFormatException ignored) {} }
                }
            }
        } catch (Exception ignored) {}
    }
    private void saveAutonomousSettings() {
        try {
            java.nio.file.Files.createDirectories(AUTONOMOUS_SETTINGS_PATH.getParent());
            String body = "# Autonomous mode settings (edited via UI)\n"
                    + "enabled=" + autonomousEnabled + "\n"
                    + "idleTimeoutSeconds=" + autonomousIdleTimeoutSeconds + "\n"
                    + "pauseBetweenStepsMs=" + autonomousPauseBetweenStepsMs + "\n";
            java.nio.file.Files.writeString(AUTONOMOUS_SETTINGS_PATH, body);
        } catch (Exception ignored) {}
    }

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

        // /cost — inline session-spend summary for the FX chat window (which can't see the Costs tab)
        if (isCostQuery(trimmed)) {
            String reply = buildCostReply();
            transcriptService.save("BOT", reply);
            return reply;
        }

        // /bypass — FX users can't reach the title-bar ⚡ toggle, so this is their way in.
        // "bypass" / "/bypass" → status, "/bypass on" → enable, "/bypass off" → disable.
        String bypassReply = tryHandleBypassCommand(trimmed);
        if (bypassReply != null) {
            transcriptService.save("BOT", bypassReply);
            return bypassReply;
        }

        // Fast-lane: bypass the main-loop queue for stateless messages (greetings,
        // math, port kills, log tweaks, project listings). Lets the user get
        // answers even while a long tool call (e.g. 90s Claude Code run) is
        // blocking the main loop.
        if (fastLane != null) {
            String fastReply = fastLane.tryHandle(trimmed);
            if (fastReply != null) {
                transcriptService.save("BOT", fastReply);
                return fastReply;
            }
        }

        // /help — concrete, canned list of slash commands + key phrases.
        // The LLM's generic "I can help with many things!" answer isn't useful;
        // this returns exact strings the user can copy-paste.
        if (isSlashHelp(trimmed)) {
            String reply = buildHelpReply();
            transcriptService.save("BOT", reply);
            return reply;
        }

        // /model — show the actual runtime chat model, provider, pricing, offline state.
        if (isSlashModel(trimmed)) {
            String reply = buildModelReply();
            transcriptService.save("BOT", reply);
            return reply;
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
            Consumer<String> asyncCallback = result -> transcriptService.save("BOT(agent)", result);
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
    /**
     * Inline cost-summary queries. Accepts slash-style ("/cost", "/spend") + the
     * natural-language variants a user might type in chat. Kept short + exact-match
     * so it can't accidentally catch a conversational question like "what was the cost of X".
     */
    private static boolean isCostQuery(String trimmed) {
        if (trimmed == null || trimmed.isEmpty()) return false;
        String m = trimmed.toLowerCase().trim();
        // Strip trailing punctuation
        while (!m.isEmpty() && ".?!".indexOf(m.charAt(m.length() - 1)) >= 0) m = m.substring(0, m.length() - 1);
        return m.equals("/cost") || m.equals("/costs") || m.equals("/spend") || m.equals("/usage")
                || m.equals("cost") || m.equals("costs") || m.equals("usage")
                || m.equals("how much did i spend") || m.equals("how much have i spent")
                || m.equals("what did i spend") || m.equals("what did i spend today")
                || m.equals("show my llm spend") || m.equals("show me my spend")
                || m.equals("how much am i spending");
    }

    /** Formats the current cost summary as a compact chat reply. Safe when TokenUsageService is absent. */
    private String buildCostReply() {
        if (tokenUsageService == null) return "Cost tracking isn't wired — check app logs.";
        com.minsbot.cost.TokenUsageService.SessionSummary s = tokenUsageService.currentSession();
        if (s.calls() == 0) {
            return "No LLM calls yet this session. Nothing spent.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("💸 This session: **$").append(String.format("%.4f", s.totalUsd())).append("**");
        sb.append(" across ").append(s.calls()).append(" call").append(s.calls() == 1 ? "" : "s");
        long totalTok = s.promptTokens() + s.completionTokens();
        sb.append(" · ").append(fmtTokens(totalTok)).append(" tokens");
        sb.append("  \n");
        sb.append("If you'd run it all on local Ollama: **$0.00** (savings $")
                .append(String.format("%.4f", s.savingsIfLocalUsd())).append(")");
        if (!s.byModel().isEmpty()) {
            sb.append("  \nBy model: ");
            boolean first = true;
            for (com.minsbot.cost.TokenUsageService.ModelBucket b : s.byModel()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(b.model()).append(" (").append(b.calls()).append("×, $")
                        .append(String.format("%.4f", b.usd())).append(")");
            }
        }
        return sb.toString();
    }

    private static String fmtTokens(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1_000_000) return String.format("%.1fK", n / 1000.0);
        return String.format("%.2fM", n / 1_000_000.0);
    }

    /**
     * Slash-style model query: exact matches only. Questions like "what model would
     * be best" still flow to the LLM.
     */
    private static boolean isSlashModel(String trimmed) {
        if (trimmed == null) return false;
        String m = trimmed.toLowerCase().trim();
        while (!m.isEmpty() && ".?!".indexOf(m.charAt(m.length() - 1)) >= 0) m = m.substring(0, m.length() - 1);
        return m.equals("/model") || m.equals("/models") || m.equals("/whoami")
                || m.equals("what model") || m.equals("what model are you")
                || m.equals("which model") || m.equals("current model")
                || m.equals("active model") || m.equals("show model");
    }

    /** Concrete reply with live runtime model details. */
    private String buildModelReply() {
        String activeProvider = (localModelTools == null) ? "unknown" : localModelTools.getActiveProviderName();
        String activeOllama = (localModelTools == null) ? "" : localModelTools.getActiveOllamaModelName();
        boolean isLocal = "ollama".equalsIgnoreCase(activeProvider);
        String effectiveModel = isLocal && activeOllama != null && !activeOllama.isBlank()
                ? activeOllama : chatModelName;

        StringBuilder sb = new StringBuilder();
        sb.append("**Model:** `").append(effectiveModel).append("`  \n");
        sb.append("**Provider:** ").append(providerLabelFor(effectiveModel, isLocal)).append("  \n");
        sb.append("**Endpoint:** ").append(endpointLabelFor(effectiveModel, isLocal)).append("  \n");
        sb.append("**Pricing (per 1M tokens):** ").append(pricingLabelFor(effectiveModel, isLocal)).append("  \n");

        if (offlineModeService != null) {
            sb.append("**Offline mode:** ").append(offlineModeService.isOffline() ? "🛡️ ON" : "OFF").append("  \n");
        }
        if (approvalService != null) {
            sb.append("**Bypass permissions:** ").append(approvalService.isBypassMode() ? "⚡ ON" : "OFF").append("  \n");
        }

        if (tokenUsageService != null) {
            com.minsbot.cost.TokenUsageService.SessionSummary s = tokenUsageService.currentSession();
            if (s.calls() > 0) {
                sb.append("\n**This session:** ").append(s.calls()).append(" call")
                        .append(s.calls() == 1 ? "" : "s")
                        .append(" · ").append(fmtTokens(s.promptTokens() + s.completionTokens()))
                        .append(" tokens · $").append(String.format("%.4f", s.totalUsd()));
            }
        }

        sb.append("\n\n_Say `switch to llama3.1:8b` to change model, or `/help` for everything else._");
        return sb.toString();
    }

    private static String providerLabelFor(String model, boolean isLocal) {
        if (isLocal) return "Ollama (local)";
        if (model == null) return "unknown";
        String s = model.toLowerCase(java.util.Locale.ROOT);
        if (s.contains("gpt") || s.contains("o1") || s.contains("o3") || s.contains("o4")) return "OpenAI (cloud)";
        if (s.contains("claude")) return "Anthropic (cloud)";
        if (s.contains("gemini")) return "Google (cloud)";
        return "cloud";
    }

    private static String endpointLabelFor(String model, boolean isLocal) {
        if (isLocal) return "`http://localhost:11434`";
        if (model == null) return "unknown";
        String s = model.toLowerCase(java.util.Locale.ROOT);
        if (s.contains("gpt") || s.contains("o1") || s.contains("o3") || s.contains("o4")) return "`api.openai.com`";
        if (s.contains("claude")) return "`api.anthropic.com`";
        if (s.contains("gemini")) return "`generativelanguage.googleapis.com`";
        return "cloud";
    }

    private static String pricingLabelFor(String model, boolean isLocal) {
        if (isLocal) return "**$0** (local — free)";
        if (model == null) return "unknown";
        String s = model.toLowerCase(java.util.Locale.ROOT).replaceAll("-\\d{4}-\\d{2}-\\d{2}$", "");
        java.util.Map<String, double[]> table = java.util.Map.ofEntries(
                java.util.Map.entry("gpt-5",            new double[]{1.25, 10.00}),
                java.util.Map.entry("gpt-5-mini",       new double[]{0.25,  2.00}),
                java.util.Map.entry("gpt-5.1",          new double[]{1.25, 10.00}),
                java.util.Map.entry("gpt-5.1-mini",     new double[]{0.25,  2.00}),
                java.util.Map.entry("gpt-5.2",          new double[]{1.25, 10.00}),
                java.util.Map.entry("gpt-5.2-mini",     new double[]{0.25,  2.00}),
                java.util.Map.entry("gpt-5.2-codex",    new double[]{1.25, 10.00}),
                java.util.Map.entry("gpt-4.1",          new double[]{2.00,  8.00}),
                java.util.Map.entry("gpt-4.1-mini",     new double[]{0.40,  1.60}),
                java.util.Map.entry("gpt-4o",           new double[]{5.00, 20.00}),
                java.util.Map.entry("gpt-4o-mini",      new double[]{0.15,  0.60}),
                java.util.Map.entry("gpt-4",            new double[]{30.00, 60.00}),
                java.util.Map.entry("gpt-4-turbo",      new double[]{10.00, 30.00}),
                java.util.Map.entry("gpt-3.5-turbo",    new double[]{0.50,  1.50}),
                java.util.Map.entry("o3",               new double[]{2.00,  8.00}),
                java.util.Map.entry("o3-mini",          new double[]{1.10,  4.40}),
                java.util.Map.entry("o4-mini",          new double[]{1.10,  4.40}),
                java.util.Map.entry("claude-opus-4",    new double[]{15.00, 75.00}),
                java.util.Map.entry("claude-sonnet-4-6",new double[]{3.00, 15.00}),
                java.util.Map.entry("claude-haiku-4-5", new double[]{1.00,  5.00}),
                java.util.Map.entry("gemini-2.5-pro",   new double[]{1.25, 10.00}),
                java.util.Map.entry("gemini-2.5-flash", new double[]{0.075, 0.30})
        );
        double[] p = table.get(s);
        if (p == null) return "_not in table — cost estimate uses fallback $1/$4_";
        return String.format("$%.2f in / $%.2f out", p[0], p[1]);
    }

    /**
     * Slash-style help: exact matches only so conversational questions like "can
     * you help me with X" still flow to the LLM rather than getting this canned list.
     */
    private static boolean isSlashHelp(String trimmed) {
        if (trimmed == null) return false;
        String m = trimmed.toLowerCase().trim();
        while (!m.isEmpty() && ".?!".indexOf(m.charAt(m.length() - 1)) >= 0) m = m.substring(0, m.length() - 1);
        return m.equals("/help") || m.equals("/commands") || m.equals("/?")
                || m.equals("what commands") || m.equals("what commands are there")
                || m.equals("list commands") || m.equals("show commands")
                || m.equals("show help") || m.equals("slash commands");
    }

    /** Concrete reply listing in-chat commands + key voice/natural-language phrases. */
    private String buildHelpReply() {
        StringBuilder sb = new StringBuilder();
        sb.append("**Chat commands**\n");
        sb.append("- `/help` — this list\n");
        sb.append("- `/cost` — this session's LLM spend + tokens + savings vs local\n");
        sb.append("- `/bypass [on|off]` — auto-approve destructive tools for this session\n");
        sb.append("- `quit` / `exit` — close Mins Bot\n");
        sb.append("- `open command center` — open the full browser UI\n\n");

        sb.append("**Natural-language things I understand**\n");
        sb.append("- `install chrome` / `install gh` — winget install any app by name\n");
        sb.append("- `switch to llama3.1:8b` — swap chat to a local Ollama model\n");
        sb.append("- `switch back to openai` — restore the cloud provider\n");
        sb.append("- `list my skill packs` — show installed SKILL.md packs\n");
        sb.append("- `use the <name> skill to …` — invoke a skill pack\n");
        sb.append("- `generate 2 images of a red car` — ComfyUI image gen (count works)\n");
        sb.append("- `monitor <url> every 30 minutes` — background URL watcher\n");
        sb.append("- `translate my clipboard to spanish` — local LLM translate\n\n");

        sb.append("**Toggles (title bar, or via chat)**\n");
        sb.append("- 🛡️ Offline mode — blocks cloud APIs + auto-switches to best local model\n");
        sb.append("- ⚡ Bypass mode — all destructive tools auto-approved until restart (`/bypass on`)\n\n");

        sb.append("**Tabs (browser view only)**\n");
        sb.append("- Models — install Ollama / ComfyUI / Piper voice packs\n");
        sb.append("- Diagnostics — system health, GPU, API keys\n");
        sb.append("- Skill Packs — 50 SKILL.md-format skills, import from URL\n");
        sb.append("- Costs — live $ spend, per-model, daily history\n");
        sb.append("- Integrations — connect Gmail / GitHub / Notion / Spotify / …\n\n");

        sb.append("**Multi-turn tasks**\n");
        sb.append("End a reply with `[[CONTINUE]]` to auto-run another turn (I do this when I need more steps to finish).");
        return sb.toString();
    }

    /**
     * Heuristic: does this model id point to a cloud provider whose endpoint offline
     * mode must refuse? Covers the three vendors whose chat models Spring AI wires
     * in this project. Local Ollama tags ({@code llama3.1:8b}, {@code mistral:7b},
     * {@code qwen2.5:7b}, etc.) return false and are allowed through offline-mode.
     */
    private static boolean isCloudModel(String modelId) {
        if (modelId == null || modelId.isBlank()) return false;
        String s = modelId.toLowerCase(java.util.Locale.ROOT);
        if (s.contains("gpt") || s.contains("o1") || s.contains("o3") || s.contains("o4")) return true;
        if (s.contains("claude")) return true;
        if (s.contains("gemini")) return true;
        return false;
    }

    /**
     * Handle {@code /bypass}, {@code /bypass on}, {@code /bypass off}, and natural-language variants.
     * Returns the reply string to short-circuit the chat flow, or {@code null} if the message isn't a
     * bypass command. Needed because the FX webview hides the title-bar toggle.
     */
    private String tryHandleBypassCommand(String trimmed) {
        if (trimmed == null || approvalService == null) return null;
        String m = trimmed.toLowerCase().trim();
        while (!m.isEmpty() && ".?!".indexOf(m.charAt(m.length() - 1)) >= 0) m = m.substring(0, m.length() - 1);

        boolean wantsStatus = m.equals("/bypass") || m.equals("bypass") || m.equals("bypass?")
                || m.equals("bypass status") || m.equals("is bypass on");
        boolean wantsOn = m.equals("/bypass on") || m.equals("bypass on") || m.equals("enable bypass")
                || m.equals("turn on bypass") || m.equals("turn bypass on")
                || m.equals("bypass permissions") || m.equals("bypass permissions on");
        boolean wantsOff = m.equals("/bypass off") || m.equals("bypass off") || m.equals("disable bypass")
                || m.equals("turn off bypass") || m.equals("turn bypass off")
                || m.equals("bypass permissions off");

        if (!wantsStatus && !wantsOn && !wantsOff) return null;

        if (wantsOn) {
            approvalService.setBypassMode(true);
            return "⚡ Bypass permissions: **ON**. Every destructive tool call is auto-approved until you restart the app or say \"/bypass off\".";
        }
        if (wantsOff) {
            approvalService.setBypassMode(false);
            return "🛡️ Bypass permissions: **OFF**. Destructive tools will prompt again.";
        }
        // status
        return approvalService.isBypassMode()
                ? "⚡ Bypass permissions: **ON** (auto-approves every destructive tool). Say \"/bypass off\" to turn off."
                : "🛡️ Bypass permissions: **OFF**. Say \"/bypass on\" to auto-approve destructive tools for this session.";
    }

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

            // Terminal output: clean banner so devs can see the plan + upcoming tool calls inline.
            String taskPreview = trimmed.length() > 80 ? trimmed.substring(0, 80) + "..." : trimmed;
            StringBuilder banner = new StringBuilder();
            banner.append("\n═══ PLAN ════════════════════════════════════════════\n");
            banner.append("  Task: ").append(taskPreview).append("\n");
            for (String line : generatedPlan.split("\\R")) {
                if (!line.isBlank()) banner.append("  ").append(line).append("\n");
            }
            banner.append("═════════════════════════════════════════════════════");
            System.out.println(banner);

            transcriptService.save("BOT(plan)", generatedPlan); // SSE delivers; no async push (avoids double-render)

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
        if (isSimpleToggleCommand(message)) return false;
        if (isSimpleFileOperation(message)) return false;
        if (isSimpleMusicCommand(message)) return false;
        if (isSimpleMapsCommand(message)) return false;
        if (isSimpleFileOpenCommand(message)) return false;
        if (isSimpleConnectedServiceCommand(message)) return false;
        if (isSimpleSystemSettingCommand(message)) return false;
        if (isImageGenerationCommand(message)) return false;
        if (isHelpCommand(message)) return false;
        if (isSimpleFactualQuestion(message)) return false;
        if (SystemContextProvider.isMessageAboutMinsbotSelfConfig(message)) return false;
        return true;
    }

    /**
     * Short factual lookups — "how much is X", "what is Y", "who is Z", "when was W" — should
     * NOT trigger the 5-step planner. They're one-shot web-search / knowledge questions that
     * the main agent can answer in one turn (or one tool call). Previously these fell through
     * and produced absurd checklists like "1. Identify which app the user is referring to…"
     * for a message as trivial as "how much is this app in $?".
     */
    private static boolean isSimpleFactualQuestion(String message) {
        String m = message.trim().toLowerCase();
        int wordCount = m.split("\\s+").length;
        if (wordCount > 14) return false;
        String[] starters = {
                "how much ", "how many ",
                "what is ", "what's ", "what are ", "what was ",
                "who is ", "who's ", "who was ",
                "when is ", "when's ", "when was ", "when will ",
                "where is ", "where's ", "where was ",
                "why is ", "why does ", "why was ",
                "is this ", "is it ", "is that ", "is there ",
                "are there ", "are these ", "are those ",
                "does this ", "does it ", "does that ",
                "did i ", "has it ", "have i ",
                "price of ", "cost of ", "how expensive "
        };
        for (String s : starters) {
            if (m.startsWith(s)) return true;
        }
        // Catch-all: short message ending in '?' without an imperative / ask.
        if (m.endsWith("?") && wordCount <= 8) {
            return !m.startsWith("can you ") && !m.startsWith("could you ") && !m.startsWith("will you ");
        }
        return false;
    }

    /**
     * "Create me an image of X", "draw a fox", "generate a logo" — single-tool-call requests
     * that should route directly to {@code generateLocalImage}. Planning produces
     * unhelpful browser-based steps (open DALL-E, etc.) and misleads the user.
     */
    private static final java.util.regex.Pattern IMAGE_GEN_PATTERN = java.util.regex.Pattern.compile(
            "\\b(generate|create|make|draw|render|paint|produce)\\b.{0,40}\\b(image|picture|illustration|artwork|logo|portrait|photo|scene|drawing|painting|render)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    private static boolean isImageGenerationCommand(String message) {
        return message != null && IMAGE_GEN_PATTERN.matcher(message).find();
    }

    /**
     * "what can you do", "help", "list your skills" — a direct self-description request.
     * One tool call, no planning or screen capture needed.
     */
    private static final java.util.regex.Pattern HELP_PATTERN = java.util.regex.Pattern.compile(
            "^\\s*(what\\s+(can\\s+you|do\\s+you)\\s+do|what\\s+are\\s+(your|the)\\s+(capabilities|skills)|" +
            "list\\s+(your\\s+)?(skills|tools|capabilities)|what\\s+tools\\s+do\\s+you\\s+have|" +
            "help|show\\s+capabilities|what's\\s+possible)\\b.*",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    private static boolean isHelpCommand(String message) {
        return message != null && HELP_PATTERN.matcher(message).matches();
    }

    /**
     * Live screen before reply — only when the user likely needs desktop/browser context
     * or the request is long enough that extra context may help.
     */
    private boolean needsLiveScreenForMessage(String message) {
        if (message == null || message.isBlank()) return false;
        if (isSimpleConversationalQuery(message)) return false;
        if (isSimpleToggleCommand(message)) return false;
        if (isSimpleFileOperation(message)) return false;
        if (isSimpleMusicCommand(message)) return false;
        if (isSimpleMapsCommand(message)) return false;
        if (isSimpleFileOpenCommand(message)) return false;
        if (isSimpleConnectedServiceCommand(message)) return false;
        if (isSimpleSystemSettingCommand(message)) return false;
        if (isImageGenerationCommand(message)) return false;
        if (isHelpCommand(message)) return false;
        if (isSimpleFactualQuestion(message)) return false;
        if (SystemContextProvider.isMessageAboutMinsbotSelfConfig(message)) return false;
        int wordCount = message.trim().split("\\s+").length;
        if (wordCount <= 2) return false;
        if (wordCount >= 16) return true;
        return SCREEN_OR_AUTOMATION_HINT.matcher(message).find();
    }

    /**
     * "Play X", "pause", "skip", "next song", "volume up" — all one-shot music tool calls.
     * No need to plan or grab a screenshot; the spotifyPlay / media-key tools are self-contained.
     */
    private static boolean isSimpleMusicCommand(String message) {
        String m = message.trim().toLowerCase();
        if (m.isEmpty()) return false;
        return m.startsWith("play ")
                || m.equals("play")
                || m.equals("pause") || m.startsWith("pause ")
                || m.equals("resume") || m.startsWith("resume ")
                || m.equals("stop") || m.startsWith("stop music")
                || m.startsWith("skip") || m.startsWith("next song") || m.startsWith("next track")
                || m.startsWith("previous") || m.startsWith("prev song")
                || m.startsWith("volume up") || m.startsWith("volume down")
                || m.startsWith("louder") || m.startsWith("quieter")
                || m.startsWith("mute") || m.startsWith("unmute")
                || m.startsWith("listen to ") || m.startsWith("put on ")
                || m.contains(" on spotify") || m.startsWith("spotify ");
    }

    /**
     * "Open maps to X", "where is X", "directions to X" — one-shot Maps tool calls,
     * no planning or screen capture needed.
     */
    private static boolean isSimpleMapsCommand(String message) {
        String m = message.trim().toLowerCase();
        if (m.isEmpty()) return false;
        return m.startsWith("open maps")
                || m.startsWith("open google maps")
                || m.startsWith("show me ") && m.contains("map")
                || m.startsWith("where is ")
                || m.startsWith("directions ")
                || m.startsWith("how do i get to ")
                || m.startsWith("navigate to ")
                || m.startsWith("take me to ") && m.contains("map");
    }

    /**
     * "Open the X PDF on my desktop", "find the report file", "open X.docx" —
     * these should go straight to file tools (listDirectory / openPath) instead of
     * the planner writing visual "click here, double-click there" steps.
     */
    private static boolean isSimpleFileOpenCommand(String message) {
        String m = message.trim().toLowerCase();
        if (m.isEmpty()) return false;
        // "open the X file/pdf/doc/xlsx/...", "open X on [my] desktop/downloads"
        boolean openFileIntent = (m.startsWith("open ") || m.startsWith("show ") || m.startsWith("find "))
                && (m.contains(".pdf") || m.contains(".doc") || m.contains(".xls")
                    || m.contains(".ppt") || m.contains(".txt") || m.contains(".csv")
                    || m.contains(".png") || m.contains(".jpg") || m.contains(".jpeg")
                    || m.contains(".mp4") || m.contains(".mp3") || m.contains(".zip")
                    || m.contains(" file") || m.contains(" pdf") || m.contains(" document")
                    || m.contains(" spreadsheet") || m.contains(" image") || m.contains(" photo"));
        boolean locationHint = m.contains("desktop") || m.contains("downloads")
                || m.contains("documents") || m.contains("folder") || m.contains("in my ");
        return openFileIntent || (m.startsWith("open ") && locationHint);
    }

    /**
     * "Check my email", "show my calendar today", "any new emails", "what's in my drive" —
     * these are one-shot API calls through the OAuth-connected Google services.
     * Skip planning so the agent just invokes the right API tool directly instead of
     * writing a "1. Open browser 2. Navigate to login page..." manual plan.
     */
    private static boolean isSimpleConnectedServiceCommand(String message) {
        String m = message.trim().toLowerCase();
        if (m.isEmpty()) return false;
        // Gmail intents
        boolean gmail = (m.contains("email") || m.contains("gmail") || m.contains("inbox") || m.contains("mail"))
                && (m.startsWith("check ") || m.startsWith("show ") || m.startsWith("read ")
                    || m.startsWith("list ") || m.startsWith("any ") || m.startsWith("how many ")
                    || m.startsWith("summarize ") || m.startsWith("what's in ") || m.startsWith("do i have ")
                    || m.contains("unread") || m.contains("new email") || m.contains("recent email"));
        // Calendar intents
        boolean calendar = (m.contains("calendar") || m.contains("meeting") || m.contains("event")
                || m.contains("appointment") || m.contains("schedule today"))
                && (m.startsWith("what") || m.startsWith("show ") || m.startsWith("check ")
                    || m.startsWith("any ") || m.startsWith("list ") || m.startsWith("do i have "));
        // Drive intents
        boolean drive = (m.contains("drive") || m.contains("google drive") || m.contains("my docs")
                || m.contains("google doc"))
                && (m.startsWith("find ") || m.startsWith("search ") || m.startsWith("list ")
                    || m.startsWith("show ") || m.startsWith("what's in "));
        // YouTube intents (the connected API)
        boolean youtube = m.contains("youtube")
                && (m.startsWith("my ") || m.startsWith("show ") || m.startsWith("list ")
                    || m.contains("subscribers") || m.contains("subscriptions") || m.contains("trending"));
        return gmail || calendar || drive || youtube;
    }

    /**
     * "Set volume to 48%", "brightness to 60", "dark mode", "turn on wifi", "lock screen",
     * "restart now", "change wallpaper to X" — one-shot Windows-settings tool calls.
     * Skip planning so the agent invokes the right tool in one go.
     */
    private static boolean isSimpleSystemSettingCommand(String message) {
        String m = message.trim().toLowerCase();
        if (m.isEmpty()) return false;
        // Volume / audio level
        if (m.matches(".*\\b(set|change|turn|put)\\b.*\\b(volume|audio|sound)\\b.*(\\d{1,3}|max|min|mute).*")) return true;
        if (m.matches(".*\\b(volume|audio)\\b.*\\b(to|at)\\b.*\\d{1,3}.*")) return true;
        if (m.matches(".*\\b(max|full|100%?)\\b.*\\b(volume|audio)\\b.*")) return true;
        // Brightness
        if (m.matches(".*\\bbrightness\\b.*(\\d{1,3}|max|min|bright|dim).*")) return true;
        if (m.startsWith("dim ") || m.startsWith("brighter") || m.equals("brighter")) return true;
        // Theme
        if (m.contains("dark mode") || m.contains("light mode") || m.contains("dark theme") || m.contains("light theme")) return true;
        if (m.contains("night light") || m.contains("blue filter") || m.contains("night mode")) return true;
        // Network
        if (m.matches(".*\\b(turn|switch)\\b.*\\b(on|off)\\b.*\\b(wifi|wi-fi|bluetooth)\\b.*")) return true;
        if (m.matches(".*\\b(wifi|wi-fi|bluetooth)\\b.*\\b(on|off)\\b.*")) return true;
        if (m.contains("enable wifi") || m.contains("disable wifi")
                || m.contains("enable bluetooth") || m.contains("disable bluetooth")) return true;
        // Power
        if (m.startsWith("lock ") || m.equals("lock screen") || m.equals("lock my pc") || m.equals("lock windows")) return true;
        if (m.equals("sleep") || m.startsWith("sleep now") || m.startsWith("put") && m.contains("sleep")) return true;
        if (m.startsWith("restart") || m.startsWith("reboot")) return true;
        if (m.startsWith("shutdown") || m.startsWith("shut down") || m.startsWith("cancel shutdown")) return true;
        if (m.contains("power plan") || m.contains("performance mode") || m.contains("battery saver")) return true;
        // Mic
        if ((m.contains("mute") || m.contains("unmute")) && (m.contains("mic") || m.contains("microphone"))) return true;
        // Explorer
        if (m.contains("hidden files") || m.contains("file extensions")) return true;
        if (m.contains("taskbar") && (m.contains("left") || m.contains("center"))) return true;
        if (m.contains("empty") && (m.contains("recycle") || m.contains("trash"))) return true;
        // Caps lock / num lock
        if (m.contains("caps lock") || m.contains("num lock") || m.contains("numlock") || m.contains("capslock")) return true;
        // Wallpaper
        if (m.contains("wallpaper") || (m.contains("background") && (m.startsWith("set ") || m.startsWith("change ")))) return true;
        // Clipboard / notifications toggles
        if (m.contains("clipboard history") && (m.contains("on") || m.contains("off") || m.contains("enable") || m.contains("disable"))) return true;
        if ((m.contains("enable") || m.contains("disable") || m.contains("turn off") || m.contains("turn on"))
                && m.contains("notification")) return true;
        return false;
    }

    private static boolean isSimpleToggleCommand(String message) {
        return SIMPLE_TOGGLE_COMMAND.matcher(message.trim()).find();
    }

    private static boolean isSimpleFileOperation(String message) {
        return SIMPLE_FILE_OPERATION.matcher(message.trim()).find();
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

                Consumer<String> asyncCallback = result -> transcriptService.save("BOT(autonomous-agent)", result);
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
                    transcriptService.save("BOT(autonomous)", reply); // SSE delivers; no async push
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
