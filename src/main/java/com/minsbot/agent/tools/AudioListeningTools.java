package com.minsbot.agent.tools;

import com.minsbot.agent.AsyncMessageService;
import com.minsbot.agent.AudioMemoryService;
import com.minsbot.agent.AudioPipelineService;
import com.minsbot.agent.GeminiLiveService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Audio listening mode — continuous background system audio capture and transcription.
 * The "ear" counterpart to ScreenWatchingTools' "eye".
 *
 * <p>Captures system audio (what's playing through speakers/headphones) every 2 seconds,
 * transcribes via Whisper, translates to English via gpt-4o-mini (fast, direct HTTP),
 * and pushes live translations to both the feed bar and the chat area.</p>
 */
@Component
public class AudioListeningTools {

    private static final Logger log = LoggerFactory.getLogger(AudioListeningTools.class);

    private static final int DEFAULT_CAPTURE_DURATION = 4;
    private static final int MAX_ROUNDS = 5400; // 3 hours at ~2s per round

    /** User-configurable capture duration (1-8 seconds), set via popup slider. */
    private volatile int captureDuration = DEFAULT_CAPTURE_DURATION;

    private final AudioMemoryService audioMemoryService;
    private final AudioPipelineService audioPipeline;
    private final AsyncMessageService asyncMessages;
    private final ToolExecutionNotifier notifier;
    private final TtsTools ttsTools;
    private final GeminiLiveService geminiLiveService;

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url:https://api.openai.com}")
    private String baseUrl;

    private HttpClient httpClient;

    private volatile boolean listening = false;
    private volatile boolean vocalMode = false;
    private volatile String activeEngine = "";
    private volatile Thread listenThread;

    /** Pending transcriptions for the UI feed. */
    private final ConcurrentLinkedQueue<String> listenFeed = new ConcurrentLinkedQueue<>();

    /** Most recent transcription — available for main AI context. */
    private volatile String latestTranscription = null;

    public AudioListeningTools(AudioMemoryService audioMemoryService,
                               AudioPipelineService audioPipeline,
                               AsyncMessageService asyncMessages, ToolExecutionNotifier notifier,
                               TtsTools ttsTools, GeminiLiveService geminiLiveService) {
        this.audioMemoryService = audioMemoryService;
        this.audioPipeline = audioPipeline;
        this.asyncMessages = asyncMessages;
        this.notifier = notifier;
        this.ttsTools = ttsTools;
        this.geminiLiveService = geminiLiveService;
    }

    @PostConstruct
    void init() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    private static final String ENGINE_WHISPER_GPT = "whisper-gpt";
    private volatile String selectedEngine = null; // null = auto-detect, or explicit engine value

    public boolean isListening() { return listening; }
    public String getActiveEngine() { return activeEngine; }
    public boolean isVocalMode() { return vocalMode; }
    public void setVocalMode(boolean vocalMode) { this.vocalMode = vocalMode; }
    public void setCaptureDuration(int seconds) { this.captureDuration = Math.max(1, Math.min(8, seconds)); }

    /** Set the translation engine. Gemini model names use Gemini Live; "whisper-gpt" uses Whisper+ChatGPT. */
    public void setEngine(String engine) {
        if (ENGINE_WHISPER_GPT.equals(engine)) {
            this.selectedEngine = ENGINE_WHISPER_GPT;
        } else if (engine != null && !engine.isBlank()) {
            this.selectedEngine = engine;
            geminiLiveService.setLiveModel(engine);
        }
    }

    /** Returns the currently selected engine value (Gemini model name or "whisper-gpt"). */
    public String getEngine() {
        return ENGINE_WHISPER_GPT.equals(selectedEngine) ? ENGINE_WHISPER_GPT : geminiLiveService.getLiveModel();
    }

    public String getLatestTranscription() { return latestTranscription; }

    /** Drain all pending listen transcriptions (called by the frontend poll endpoint). */
    public List<String> drainTranscriptions() {
        List<String> result = new ArrayList<>();
        String msg;
        while ((msg = listenFeed.poll()) != null) {
            result.add(msg);
        }
        return result;
    }

    @Tool(description = "Start listening to system audio in the background. "
            + "Captures what is playing through the speakers/headphones every 2 seconds, "
            + "translates to English, and shows live translations in the UI chat. "
            + "Use when the user says 'listen to what I'm listening', 'what song is this', "
            + "'listen to my meeting', or 'turn on your ears'. "
            + "Call stopListening() to stop.")
    public String startListening() {
        notifier.notify("Starting audio listening...");

        if (listening) {
            return "Already listening. Call stopListening() first to restart.";
        }

        listening = true;
        latestTranscription = null;

        // Choose engine based on user selection
        boolean useGeminiLive;
        if (ENGINE_WHISPER_GPT.equals(selectedEngine)) {
            useGeminiLive = false;
        } else {
            useGeminiLive = geminiLiveService.isAvailable();
        }
        String engine = useGeminiLive ? "Gemini Live (real-time streaming)" : "Whisper + GPT (chunked)";
        int captureInterval = captureDuration;
        log.info("[ListenMode] Starting — engine: {}, capture: {}s, max rounds: {}", engine, captureInterval, MAX_ROUNDS);

        Runnable loop = useGeminiLive ? this::runGeminiLiveListenLoop : this::runListenLoop;
        listenThread = new Thread(loop, "audio-listen");
        listenThread.setDaemon(true);
        listenThread.start();

        return "Listening mode started (" + engine + "). Capturing system audio every " + captureInterval
                + " seconds with live English translation. Say 'stop listening' to end.";
    }

    @Tool(description = "Stop listening to system audio. "
            + "Use when the user says 'stop listening', 'turn off your ears', or 'stop hearing'.")
    public String stopListening() {
        notifier.notify("Stopping audio listening...");
        if (!listening) {
            return "Listening mode is not active.";
        }
        log.info("[ListenMode] Stop requested");
        listening = false;
        activeEngine = "";
        geminiLiveService.disconnect();
        if (listenThread != null) {
            listenThread.interrupt();
        }
        return "Listening mode stopped.";
    }

    // ═══ Fast translation + gender detection via gpt-4o-mini (direct HTTP) ═══

    /** Simple result holder for translation + speaker gender. */
    private record TranslationResult(String translation, String gender) {}

    /**
     * Translate text to English AND detect speaker gender using gpt-4o-mini.
     * Always detects gender so it can be shown in the UI.
     */
    private TranslationResult translateAndDetectGender(String text) {
        if (text == null || text.isBlank()) return new TranslationResult(text, "unknown");
        if (apiKey == null || apiKey.isBlank() || httpClient == null) return new TranslationResult(text, "unknown");

        try {
            String systemPrompt = "You are a translator. Translate the user's text to English and detect the speaker's likely gender from context clues (pronouns, names, vocal context). "
                    + "Return ONLY a JSON object with two fields: "
                    + "\"translation\" (the English text) and \"gender\" (exactly one of: \"male\", \"female\", or \"unknown\"). "
                    + "Example: {\"translation\":\"My dream is to be a developer.\",\"gender\":\"male\"} "
                    + "If already English, return original text as translation. No extra text outside the JSON.";

            String requestBody = String.format(
                    "{\"model\":\"gpt-4o-mini\",\"temperature\":0,\"max_tokens\":500,\"messages\":"
                            + "[{\"role\":\"system\",\"content\":\"%s\"},{\"role\":\"user\",\"content\":\"%s\"}]}",
                    escapeJson(systemPrompt), escapeJson(text));

            String url = baseUrl.endsWith("/")
                    ? baseUrl + "v1/chat/completions"
                    : baseUrl + "/v1/chat/completions";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(8))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.debug("[ListenMode] Translation API returned HTTP {}", response.statusCode());
                return new TranslationResult(text, "unknown");
            }

            String content = extractContent(response.body());
            if (content == null || content.isBlank()) {
                return new TranslationResult(text, "unknown");
            }

            // Parse JSON response for translation + gender
            if (content.contains("\"translation\"")) {
                String translation = extractJsonField(content, "translation");
                String gender = extractJsonField(content, "gender");
                if (translation == null || translation.isBlank()) translation = text;
                if (gender == null || gender.isBlank()) gender = "unknown";
                log.info("[ListenMode] Translated: {} (gender={})", translation, gender);
                return new TranslationResult(translation.trim(), gender.toLowerCase());
            } else {
                log.info("[ListenMode] Translated (plain): {}", content);
                return new TranslationResult(content.trim(), "unknown");
            }
        } catch (Exception e) {
            log.debug("[ListenMode] Translation failed: {}", e.getMessage());
        }
        return new TranslationResult(text, "unknown");
    }

    /** Extract a string field value from simple JSON like {"key":"value"}. */
    private static String extractJsonField(String json, String field) {
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) return null;
        int start = json.indexOf('"', colon + 1);
        if (start < 0) return null;
        start++;
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') break;
            if (c == '\\' && i + 1 < json.length()) {
                i++;
                char next = json.charAt(i);
                if (next == 'n') sb.append('\n');
                else if (next == 't') sb.append('\t');
                else sb.append(next);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Extract assistant content from OpenAI chat completions response. */
    private static String extractContent(String json) {
        int choicesIdx = json.indexOf("\"choices\"");
        if (choicesIdx < 0) return null;
        int contentIdx = json.indexOf("\"content\":", choicesIdx);
        if (contentIdx < 0) return null;

        int start = json.indexOf('"', contentIdx + 10);
        if (start < 0) return null;
        start++;

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') break;
            if (c == '\\' && i + 1 < json.length()) {
                i++;
                char next = json.charAt(i);
                if (next == 'n') sb.append('\n');
                else if (next == 't') sb.append('\t');
                else sb.append(next);
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ═══ Gemini Live listen loop (streaming WebSocket) ═══

    /**
     * Gemini Live listen loop: uses continuous AudioPipeline for zero-gap capture.
     * Reads overlapping chunks from the ring buffer — no subprocess overhead per chunk.
     * Falls back to legacy per-chunk capture if pipeline fails to start.
     */
    private void runGeminiLiveListenLoop() {
        int round = 0;
        int consecutiveSilent = 0;
        boolean pipelineMode = false;
        activeEngine = "Gemini Live";

        try {
            // Connect to Gemini Live WebSocket
            geminiLiveService.connect(new GeminiLiveService.LiveTranscriptionListener() {
                @Override
                public void onTurnComplete(String inputTranscription, String translation) {
                    if (translation == null || translation.isBlank()) return;

                    String displayText = translation.trim();

                    log.info("[ListenMode/Gemini] Input: {} | Translation: {}",
                            inputTranscription, displayText.length() > 300 ? displayText.substring(0, 300) : displayText);

                    if (displayText.isBlank()) return;

                    // Dedup
                    if (displayText.equals(latestTranscription)) return;

                    log.info("[ListenMode/Gemini] → {}", displayText);

                    listenFeed.add(displayText);
                    asyncMessages.push(displayText);
                    latestTranscription = displayText;

                    // Vocal mode: speak the translation
                    if (vocalMode && !displayText.isBlank()) {
                        ttsTools.speakAsync(displayText);
                    }
                }

                @Override
                public void onError(String error) {
                    log.warn("[ListenMode/Gemini] Error: {} — will fall back to Whisper+GPT", error);
                }
            });
        } catch (Exception e) {
            log.warn("[ListenMode/Gemini] Connection failed: {} — falling back to Whisper+GPT", e.getMessage());
            activeEngine = "Whisper+GPT (fallback)";
            runListenLoop();
            return;
        }

        // Try to start continuous pipeline (zero-gap capture)
        try {
            audioPipeline.start();
            // Short warmup — pipeline starts filling the ring buffer immediately
            Thread.sleep(2000);
            pipelineMode = audioPipeline.isRunning();
            if (pipelineMode) {
                log.info("[ListenMode/Gemini] Pipeline mode — continuous streaming, ~1s increments");
            }
        } catch (Exception e) {
            log.info("[ListenMode/Gemini] Pipeline failed to start: {} — using legacy per-chunk capture", e.getMessage());
        }

        try {
            while (listening && round < MAX_ROUNDS) {
                round++;

                // Skip capture while TTS is playing (feedback prevention)
                if (vocalMode && ttsTools.isSpeaking()) {
                    log.debug("[ListenMode/Gemini] Round {} — skipping (TTS playing)", round);
                    try { Thread.sleep(500); } catch (InterruptedException e) { break; }
                    continue;
                }

                // If WebSocket dropped, fall back to Whisper+GPT
                if (!geminiLiveService.isConnected()) {
                    log.warn("[ListenMode/Gemini] WebSocket lost — falling back to Whisper+GPT");
                    activeEngine = "Whisper+GPT (fallback)";
                    audioPipeline.stop();
                    runListenLoop();
                    return;
                }

                try {
                    byte[] pcm;
                    if (pipelineMode && audioPipeline.isRunning()) {
                        // Pipeline mode: read only NEW audio since last call (continuous stream)
                        pcm = audioPipeline.readNewAudio();
                    } else {
                        // Legacy fallback: per-chunk subprocess capture
                        if (pipelineMode) {
                            log.warn("[ListenMode/Gemini] Pipeline died, falling back to legacy capture");
                            pipelineMode = false;
                        }
                        pcm = audioMemoryService.captureRawPcm(captureDuration);
                    }

                    if (pcm != null && pcm.length > 0) {
                        consecutiveSilent = 0;
                        geminiLiveService.sendAudio(pcm);
                    } else {
                        consecutiveSilent++;
                        if (consecutiveSilent == 60) {
                            listenFeed.add("(no audio detected — is something playing?)");
                        }
                    }
                } catch (Exception e) {
                    log.warn("[ListenMode/Gemini] Round {} — capture failed: {}", round, e.getMessage());
                }

                // Pipeline: send every ~1s for continuous stream. Legacy: capture is the pacer.
                if (pipelineMode) {
                    try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
                }
            }
        } catch (Exception e) {
            if (!(e instanceof InterruptedException)) {
                log.warn("[ListenMode/Gemini] Loop ended: {}", e.getMessage());
            }
        } finally {
            audioPipeline.stop();
            geminiLiveService.disconnect();
            listening = false;
            listenThread = null;
            log.info("[ListenMode/Gemini] Ended after {} rounds", round);
            if (round >= MAX_ROUNDS) {
                listenFeed.add("Listening mode ended (reached maximum duration).");
            }
        }
    }

    // ═══ Whisper+GPT listen loop (fallback) ═══

    /**
     * Listen loop: capture 2s audio → fire off translation in background → immediately
     * start next capture. No idle gap between rounds — translation happens concurrently.
     */
    private void runListenLoop() {
        if (activeEngine.isEmpty()) activeEngine = "Whisper+GPT";
        int round = 0;
        int consecutiveSilent = 0;
        ExecutorService translator = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "listen-translate");
            t.setDaemon(true);
            return t;
        });

        try {
            while (listening && round < MAX_ROUNDS) {
                round++;

                // Skip capture while TTS is playing — prevents feedback loop
                // (bot hearing its own voice → re-transcribing → re-speaking)
                if (vocalMode && ttsTools.isSpeaking()) {
                    log.debug("[ListenMode] Round {} — skipping capture (TTS playing)", round);
                    try { Thread.sleep(500); } catch (InterruptedException e) { break; }
                    continue;
                }

                log.info("[ListenMode] Round {}/{} — capturing {}s audio...", round, MAX_ROUNDS, captureDuration);

                try {
                    String transcription = audioMemoryService.captureNow(captureDuration);

                    if (transcription != null && !transcription.isBlank()) {
                        consecutiveSilent = 0;
                        String display = transcription.length() > 300
                                ? transcription.substring(0, 300) + "..."
                                : transcription;

                        log.info("[ListenMode] Round {} — captured ({} chars): {}",
                                round, transcription.length(), display);

                        // Translate in background — don't block next capture
                        String captured = display;
                        translator.submit(() -> {
                            TranslationResult result = translateAndDetectGender(captured);
                            String translated = result.translation();
                            String gender = result.gender();

                            // Dedup: skip if same as the last translation (feedback artifact)
                            if (translated != null && translated.equals(latestTranscription)) {
                                log.debug("[ListenMode] Skipping duplicate translation");
                                return;
                            }

                            // Prefix with speaker gender
                            String feedText = translated;
                            if ("male".equals(gender)) feedText = "male: " + translated;
                            else if ("female".equals(gender)) feedText = "female: " + translated;

                            listenFeed.add(feedText);
                            asyncMessages.push(feedText);
                            latestTranscription = translated;

                            // Vocal mode: speak the translation with gender-matched voice
                            if (vocalMode && translated != null && !translated.isBlank()) {
                                ttsTools.speakAsyncWithVoice(translated, gender);
                            }
                        });
                    } else {
                        consecutiveSilent++;
                        log.debug("[ListenMode] Round {} — silence ({} consecutive)",
                                round, consecutiveSilent);

                        if (consecutiveSilent == 60) { // ~2 min at 2s captures
                            listenFeed.add("(no audio detected — is something playing?)");
                        }
                    }
                } catch (Exception e) {
                    log.warn("[ListenMode] Round {} — capture failed: {}", round, e.getMessage());
                }

                // No sleep — next capture starts immediately
                // (the 2s capture itself is the natural pacing)
            }
        } catch (Exception e) {
            if (!(e instanceof InterruptedException)) {
                log.warn("[ListenMode] Loop ended with exception: {}", e.getMessage());
            }
        } finally {
            listening = false;
            listenThread = null;
            translator.shutdown();
            log.info("[ListenMode] Ended after {} rounds", round);
            if (round >= MAX_ROUNDS) {
                listenFeed.add("Listening mode ended (reached maximum duration).");
            }
        }
    }
}
