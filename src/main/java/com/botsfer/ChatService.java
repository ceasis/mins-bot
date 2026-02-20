package com.botsfer;

import com.botsfer.agent.PcAgentService;
import com.botsfer.agent.SystemContextProvider;
import com.botsfer.agent.WorkingSoundService;
import com.botsfer.agent.tools.*;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final String AUDIO_RESULT_PREFIX = "__AUDIO_RESULT__";

    private final TranscriptService transcriptService;
    private final PcAgentService pcAgent;
    private final SystemContextProvider systemCtx;

    // Tool beans
    private final SystemTools systemTools;
    private final BrowserTools browserTools;
    private final FileTools fileTools;
    private final FileSystemTools fileSystemTools;
    private final TaskStatusTool taskStatusTool;
    private final ChatHistoryTool chatHistoryTool;
    private final ClipboardTools clipboardTools;
    private final MemoryTools memoryTools;
    private final ImageTools imageTools;
    private final HuggingFaceImageTool huggingFaceImageTool;
    private final DirectivesTools directivesTools;
    private final DirectiveDataTools directiveDataTools;
    private final WebScraperTools webScraperTools;
    private final PlaywrightTools playwrightTools;
    private final WeatherTools weatherTools;
    private final NotificationTools notificationTools;
    private final CalculatorTools calculatorTools;
    private final QrTools qrTools;
    private final DownloadTools downloadTools;
    private final HashTools hashTools;
    private final UnitConversionTools unitConversionTools;
    private final TimerTools timerTools;
    private final TtsTools ttsTools;
    private final PdfTools pdfTools;
    private final EmailTools emailTools;
    private final ScheduledTaskTools scheduledTaskTools;
    private final SummarizationTools summarizationTools;
    private final ModelSwitchTools modelSwitchTools;
    private final ExportTools exportTools;
    private final GlobalHotkeyService globalHotkeyService;
    private final PluginLoaderService pluginLoaderService;
    private final SystemTrayService systemTrayService;
    private final LocalModelTools localModelTools;
    private final ToolExecutionNotifier toolNotifier;
    private final WorkingSoundService workingSound;

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

    /** Async results from background agent tasks (polled by frontend). */
    private final ConcurrentLinkedQueue<String> asyncResults = new ConcurrentLinkedQueue<>();

    public ChatService(TranscriptService transcriptService,
                       PcAgentService pcAgent,
                       SystemContextProvider systemCtx,
                       SystemTools systemTools,
                       BrowserTools browserTools,
                       FileTools fileTools,
                       FileSystemTools fileSystemTools,
                       TaskStatusTool taskStatusTool,
                       ChatHistoryTool chatHistoryTool,
                       ClipboardTools clipboardTools,
                       MemoryTools memoryTools,
                       ImageTools imageTools,
                       HuggingFaceImageTool huggingFaceImageTool,
                       DirectivesTools directivesTools,
                       DirectiveDataTools directiveDataTools,
                       WebScraperTools webScraperTools,
                       PlaywrightTools playwrightTools,
                       WeatherTools weatherTools,
                       NotificationTools notificationTools,
                       CalculatorTools calculatorTools,
                       QrTools qrTools,
                       DownloadTools downloadTools,
                       HashTools hashTools,
                       UnitConversionTools unitConversionTools,
                       TimerTools timerTools,
                       TtsTools ttsTools,
                       PdfTools pdfTools,
                       EmailTools emailTools,
                       ScheduledTaskTools scheduledTaskTools,
                       SummarizationTools summarizationTools,
                       ModelSwitchTools modelSwitchTools,
                       ExportTools exportTools,
                       GlobalHotkeyService globalHotkeyService,
                       PluginLoaderService pluginLoaderService,
                       SystemTrayService systemTrayService,
                       LocalModelTools localModelTools,
                       ToolExecutionNotifier toolNotifier,
                       WorkingSoundService workingSound) {
        this.transcriptService = transcriptService;
        this.pcAgent = pcAgent;
        this.systemCtx = systemCtx;
        this.systemTools = systemTools;
        this.browserTools = browserTools;
        this.fileTools = fileTools;
        this.fileSystemTools = fileSystemTools;
        this.taskStatusTool = taskStatusTool;
        this.chatHistoryTool = chatHistoryTool;
        this.clipboardTools = clipboardTools;
        this.memoryTools = memoryTools;
        this.imageTools = imageTools;
        this.huggingFaceImageTool = huggingFaceImageTool;
        this.directivesTools = directivesTools;
        this.directiveDataTools = directiveDataTools;
        this.webScraperTools = webScraperTools;
        this.playwrightTools = playwrightTools;
        this.weatherTools = weatherTools;
        this.notificationTools = notificationTools;
        this.calculatorTools = calculatorTools;
        this.qrTools = qrTools;
        this.downloadTools = downloadTools;
        this.hashTools = hashTools;
        this.unitConversionTools = unitConversionTools;
        this.timerTools = timerTools;
        this.ttsTools = ttsTools;
        this.pdfTools = pdfTools;
        this.emailTools = emailTools;
        this.scheduledTaskTools = scheduledTaskTools;
        this.summarizationTools = summarizationTools;
        this.modelSwitchTools = modelSwitchTools;
        this.exportTools = exportTools;
        this.globalHotkeyService = globalHotkeyService;
        this.pluginLoaderService = pluginLoaderService;
        this.systemTrayService = systemTrayService;
        this.localModelTools = localModelTools;
        this.toolNotifier = toolNotifier;
        this.workingSound = workingSound;
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

    /** Returns and removes the next async result, or null if none. */
    public String pollAsyncResult() {
        return asyncResults.poll();
    }

    /** Returns and removes all pending tool execution status messages. */
    public java.util.List<String> drainToolStatus() {
        return toolNotifier.drain();
    }

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
        toolNotifier.clear();
        transcriptService.save("USER", trimmed);

        Consumer<String> asyncCallback = result -> {
            transcriptService.save("BOT(agent)", result);
            asyncResults.add(result);
        };

        // 1. Spring AI tool-calling path
        if (chatClient != null) {
            try {
                fileTools.setAsyncCallback(asyncCallback);
                workingSound.start();

                String reply = chatClient.prompt()
                        .system(systemCtx.buildSystemMessage())
                        .user(trimmed)
                        // OpenAI allows max 128 tools per request; we have 140+ so exclude optional/niche ones to stay under limit
// Excluded: modelSwitchTools, globalHotkeyService, pluginLoaderService, systemTrayService, exportTools
.tools(systemTools, browserTools, fileTools, fileSystemTools, taskStatusTool, chatHistoryTool, clipboardTools, memoryTools, imageTools, huggingFaceImageTool, directivesTools, directiveDataTools, webScraperTools, playwrightTools, weatherTools, notificationTools, calculatorTools, qrTools, downloadTools, hashTools, unitConversionTools, timerTools, ttsTools, pdfTools, emailTools, scheduledTaskTools, summarizationTools, localModelTools)
                        .call()
                        .content();

                workingSound.stop();

                if (reply != null && !reply.isBlank()) {
                    transcriptService.save("BOT", reply);
                    return reply;
                }
            } catch (Exception e) {
                workingSound.stop();
                log.error("[ChatService] Spring AI error: {}", e.getMessage(), e);
                // Tell the user what went wrong instead of silently falling back
                String errorReply = "AI error: " + e.getMessage();
                transcriptService.save("BOT(error)", errorReply);
                return errorReply;
            }
        }

        // 2. Fallback: regex-based command matching (works without API key)
        String agentReply = pcAgent.tryExecute(trimmed, asyncCallback);
        if (agentReply != null) {
            transcriptService.save("BOT", agentReply);
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

    private String transcribeAudio(byte[] wavAudio) throws Exception {
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

        autonomousRunning = true;
        int step = 0;
        try {
            log.info("[Autonomous] User idle — starting continuous directive work.");

            while (step < 100) {
                step++;

                String prompt = buildAutonomousPrompt(directives, step);

                Consumer<String> asyncCallback = result -> {
                    transcriptService.save("BOT(autonomous-agent)", result);
                    asyncResults.add(result);
                };
                fileTools.setAsyncCallback(asyncCallback);
                workingSound.start();

                String reply = chatClient.prompt()
                        .system(systemCtx.buildSystemMessage())
                        .user(prompt)
                        .tools(systemTools, browserTools, fileTools, fileSystemTools,
                               taskStatusTool, chatHistoryTool, clipboardTools,
                               imageTools, directivesTools, directiveDataTools,
                               webScraperTools, playwrightTools, emailTools,
                               scheduledTaskTools, summarizationTools, exportTools,
                               localModelTools)
                        .call()
                        .content();

                workingSound.stop();

                if (reply != null && !reply.isBlank()) {
                    // Check for "done" signal from the AI
                    if (reply.toLowerCase().contains("all directives addressed")) {
                        transcriptService.save("BOT(autonomous)", reply);
                        asyncResults.add(reply);
                        log.info("[Autonomous] AI signaled completion at step {}.", step);
                        break;
                    }
                    transcriptService.save("BOT(autonomous)", reply);
                    asyncResults.add(reply);
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
                    Review the primary directives below and plan how to accomplish them.
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
                    - Do NOT write to primary_directives.dat. That file is ONLY for directive definitions.
                    - Use saveDirectiveFinding(directiveName, content) to save text findings into a \
                    per-directive folder: ~/mins_bot_data/directive_{name}/
                    - Use saveDirectiveScreenshot(directiveName) to capture the current screen as an image.
                    - Use searchAndDownloadImages, downloadFile (to path), or downloadFileToFolder (to directive folder) to save images from the web.
                    - Use a short, descriptive directiveName derived from the directive \
                    (e.g. "search-condo-new-york", "make-money-online").
                    - Use listDirectiveData(directiveName) to see what you've gathered so far.
                    - Each directive gets its own folder. All gathered data (text, images) goes there.

                    Report what you did and what you plan to do next. Be concise.
                    If all directives are personality/tone settings with nothing actionable, return an empty response.

                    DIRECTIVES:
                    """ + directives;
        }
        return """
                [Autonomous step %d] You are still in AUTONOMOUS MODE. The user is still away.

                Continue working on the primary directives. Use listDirectiveData to review \
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
                - Do NOT write to primary_directives.dat — that file is only for directive definitions.

                Report briefly what you did this step. Be concise.
                If you've completed all actionable directives or there's nothing more to do, \
                say "All directives addressed" so the system knows to stop.

                DIRECTIVES:
                """.formatted(step) + directives;
    }

}
