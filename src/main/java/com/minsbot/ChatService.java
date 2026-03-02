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
import java.util.function.Consumer;

@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final String AUDIO_RESULT_PREFIX = "__AUDIO_RESULT__";

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
            - Max 6 steps. Combine related actions into one step if needed.

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

    // Audio transcription properties (still uses raw HTTP)
    @Value("${app.openai.api-key:}")
    private String openAiApiKey;
    @Value("${app.openai.base-url:https://api.openai.com/v1}")
    private String openAiBaseUrl;
    @Value("${app.openai.transcription-model:gpt-4o-mini-transcribe}")
    private String openAiTranscriptionModel;

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
        autonomousConcludedAllAddressed = false; // allow autonomous to run again after user input
        toolNotifier.clear();
        transcriptService.save("USER", trimmed);

        // Cancel any pending 30-second quit when user sends a new message
        quitService.cancelPendingQuit();

        // Stop any currently playing TTS audio so the bot can respond to new input
        ttsTools.stopPlayback();

        // Quit command: reply and schedule quit in 30 sec if no response
        if (isQuitCommand(trimmed)) {
            transcriptService.save("BOT", QUIT_REPLY);
            quitService.scheduleQuitIn30Sec();
            return QUIT_REPLY;
        }

        Consumer<String> asyncCallback = result -> {
            transcriptService.save("BOT(agent)", result);
            asyncMessages.push(result);
        };

        // 1. Spring AI tool-calling path
        if (chatClient != null) {
            try {
                // Planning pre-step: generate numbered plan (no tools, fast)
                String generatedPlan = null;
                if (planningEnabled && needsPlanning(trimmed)) {
                    try {
                        String plan = chatClient.prompt()
                                .system(PLANNING_PROMPT)
                                .user(trimmed)
                                .call()
                                .content();
                        if (plan != null && !plan.isBlank()
                                && !plan.strip().equalsIgnoreCase("SKIP")) {
                            generatedPlan = plan.strip();
                            asyncMessages.push(generatedPlan);
                            transcriptService.save("BOT(plan)", generatedPlan);

                            // Save plan to todolist.txt
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
                                log.info("[ChatService] Plan saved to todolist.txt");
                            } catch (Exception e) {
                                log.debug("[ChatService] Could not save plan to todolist.txt: {}", e.getMessage());
                            }
                        }
                    } catch (Exception e) {
                        log.debug("[ChatService] Planning call failed (non-critical): {}", e.getMessage());
                    }
                }

                fileTools.setAsyncCallback(asyncCallback);
                workingSound.start();

                // Auto-capture live screen state with Gemini reasoning
                String systemMessage = systemCtx.buildSystemMessage();

                // If no plan was generated, check if this is a "continue" message with pending tasks
                if (generatedPlan == null && isResumeCommand(trimmed)) {
                    try {
                        java.nio.file.Path todoPath = java.nio.file.Paths.get(
                                System.getProperty("user.home"), "mins_bot_data", "todolist.txt");
                        if (java.nio.file.Files.exists(todoPath)) {
                            String todoContent = java.nio.file.Files.readString(todoPath);
                            if (todoContent.contains("[PENDING]")) {
                                // Extract the last task block with pending items
                                String[] blocks = todoContent.split("--- Task:");
                                for (int b = blocks.length - 1; b >= 0; b--) {
                                    if (blocks[b].contains("[PENDING]")) {
                                        generatedPlan = "--- Task:" + blocks[b].trim();
                                        log.info("[ChatService] Resuming pending task from todolist.txt");
                                        break;
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.debug("[ChatService] Could not read todolist.txt: {}", e.getMessage());
                    }
                }

                // Inject the plan into the system message so the main model knows what to execute
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
                try {
                    String screenAnalysis = screenStateService.captureAndAnalyze(trimmed);
                    if (screenAnalysis != null && !screenAnalysis.isBlank()) {
                        systemMessage += "\n\nLIVE SCREEN ANALYSIS (captured BEFORE your actions — "
                                + "this shows what was on screen when the user sent their message):\n"
                                + screenAnalysis + "\n"
                                + "\nCRITICAL: Use ONLY the names from this analysis for initial actions. "
                                + "AFTER any action that changes the screen (openUrl, click, navigate, drag), "
                                + "this analysis is STALE — you MUST take a fresh takeScreenshot() to see "
                                + "the updated screen before your next action. "
                                + "If a plan is provided above, EXECUTE it by calling findAndDragElement for EACH step. "
                                + "Do NOT skip any steps. Do NOT claim success without calling the tools.\n";
                        log.info("[ChatService] Injected Gemini screen analysis ({} chars)", screenAnalysis.length());
                    }
                } catch (Exception e) {
                    log.warn("[ChatService] Screen analysis failed: {}", e.getMessage());
                }

                // Inject latest watch mode observation so the AI has accurate screen context
                if (screenWatchingTools.isWatching()) {
                    String watchObs = screenWatchingTools.getLatestObservation();
                    if (watchObs != null && !watchObs.isBlank()) {
                        systemMessage += "\n\nWATCH MODE IS ACTIVE — Latest screen observation:\n"
                                + watchObs + "\n"
                                + "Use this observation to answer the user's question. "
                                + "Do NOT take a new screenshot — trust this observation.\n";
                        log.info("[ChatService] Injected watch observation into context: {}", watchObs);
                    }
                }

                final int MAX_RETRIES = 3;
                final long RETRY_DELAY_MS = 3000;
                Exception lastError = null;
                final String finalSystemMessage = systemMessage;

                for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                    try {
                        String reply = chatClient.prompt()
                                .system(finalSystemMessage)
                                .user(trimmed)
                                .tools(toolRouter.selectTools(trimmed))
                                .call()
                                .content();

                        workingSound.stop();

                        if (reply != null && !reply.isBlank()) {
                            transcriptService.save("BOT", reply);
                            autoSpeak(reply);
                            return reply;
                        }
                        break; // empty reply, fall through to fallback
                    } catch (Exception e) {
                        lastError = e;
                        boolean isNetworkError = e instanceof org.springframework.web.client.ResourceAccessException
                                || (e.getCause() != null && (e.getCause() instanceof java.net.SocketException
                                    || e.getCause() instanceof java.io.IOException));

                        if (isNetworkError && attempt < MAX_RETRIES) {
                            log.warn("[ChatService] AI call failed (attempt {}/{}): {} — retrying in {}s",
                                    attempt, MAX_RETRIES, e.getMessage(), RETRY_DELAY_MS / 1000);
                            asyncMessages.push("Connection error. Retrying... (attempt " + (attempt + 1) + "/" + MAX_RETRIES + ")");
                            try { Thread.sleep(RETRY_DELAY_MS); } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        } else {
                            workingSound.stop();
                            log.error("[ChatService] Spring AI error (attempt {}/{}): {}", attempt, MAX_RETRIES, e.getMessage(), e);
                            String errorReply = "AI error: " + e.getMessage();
                            if (attempt > 1) errorReply = "AI error after " + attempt + " attempts: " + e.getMessage();
                            transcriptService.save("BOT(error)", errorReply);
                            return errorReply;
                        }
                    }
                }

                workingSound.stop();
            } catch (Exception e) {
                workingSound.stop();
                log.error("[ChatService] Unexpected error: {}", e.getMessage(), e);
                String errorReply = "AI error: " + e.getMessage();
                transcriptService.save("BOT(error)", errorReply);
                return errorReply;
            }
        }

        // 2. Fallback: regex-based command matching (works without API key)
        String agentReply = pcAgent.tryExecute(trimmed, asyncCallback);
        if (agentReply != null) {
            transcriptService.save("BOT", agentReply);
            autoSpeak(agentReply);
            return agentReply;
        }

        // 3. No AI configured and no regex match
        if (chatClient == null) {
            String noAiReply = "ChatClient is null. AI is not connected. Set your OpenAI API key in application-secrets.properties (project root) or set the OPENAI_API_KEY environment variable, then restart.";
            transcriptService.save("BOT", noAiReply);
            return noAiReply;
        }

        // 4. AI returned empty — shouldn't normally happen
        String fallback = "I'm not sure how to respond to that. Could you rephrase?";
        transcriptService.save("BOT", fallback);
        return fallback;
    }

    /** Auto-speak the reply if TTS auto_speak is enabled. Non-blocking (runs on background thread). */
    private void autoSpeak(String reply) {
        if (ttsTools.isAutoSpeak()) {
            ttsTools.speakAsync(reply);
        }
    }

    /** True if the user message is a quit request (e.g. "quit", "exit", "close mins bot"). */
    private static boolean isQuitCommand(String trimmed) {
        if (trimmed == null || trimmed.isEmpty()) return false;
        String lower = trimmed.toLowerCase();
        return lower.equals("quit") || lower.equals("exit")
                || lower.equals("close") || lower.equals("close bot")
                || lower.equals("close mins bot") || lower.equals("exit mins bot");
    }

    /** Returns true if the message warrants a planning pre-step. Only skips very short greetings. */
    private boolean needsPlanning(String message) {
        if (message == null || message.isBlank()) return false;
        // Skip only very short messages (1-2 words: "hi", "thanks", "ok")
        int wordCount = message.trim().split("\\s+").length;
        if (wordCount <= 2) return false;
        // Let the planner decide — it returns SKIP for simple questions
        return true;
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
        if (!autonomousEnabled || chatClient == null || autonomousRunning) return;

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

                    WEB RESEARCH — you have TWO levels of headless browsing (no visible window):

                    LEVEL 1 — Playwright (real browser, renders JavaScript, preferred for dynamic sites):
                    - browsePage(url) — navigate with a real Chromium browser and get rendered text.
                    - browseAndGetImages(url) — extract all image URLs after JS renders.
                    - browseAndGetLinks(url) — extract all links after JS renders.
                    - browseSearchAndDownloadImages(query, directiveName, maxImages) — search Google/Bing \
                    Images with a real browser, find full-size image URLs, download them to the directive folder.
                    - screenshotPage(url, directiveName) — take a full-page screenshot and save it.
                    - browseAndClick(url, selector) — click an element on a page.
                    - browseAndFill(url, selector, value, submit) — fill a form and optionally submit.

                    LEVEL 2 — HTTP fetch (lighter, faster, for simple/static pages):
                    - fetchPageText(url) — simple HTTP fetch and strip HTML.
                    - extractImageUrls(url), extractLinks(url) — regex-based extraction.
                    - searchAndDownloadImages(query, directiveName, maxImages) — HTTP-based image search.
                    - downloadFileToFolder(url, filename, directiveName) — download any file from a URL.
                    - fetchPageWithImages(url) — get both text and image URLs.

                    PREFER Playwright tools (browseSearchAndDownloadImages, browsePage) for image search \
                    and JS-heavy sites. Use HTTP fetch tools as a faster fallback for simple pages.
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
                Playwright (preferred): browsePage, browseAndGetImages, browseAndGetLinks, \
                browseSearchAndDownloadImages, screenshotPage, browseAndClick, browseAndFill.
                HTTP fetch (lighter): fetchPageText, extractImageUrls, extractLinks, fetchPageWithImages.
                Downloads: browseSearchAndDownloadImages, searchAndDownloadImages, downloadFile, downloadFileToFolder.

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
