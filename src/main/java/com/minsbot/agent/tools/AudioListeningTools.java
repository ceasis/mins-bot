package com.minsbot.agent.tools;

import com.minsbot.agent.AsyncMessageService;
import com.minsbot.agent.AudioMemoryService;
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
 * <p>Captures system audio (what's playing through speakers/headphones) every 5 seconds,
 * transcribes via Whisper, translates to English via gpt-4o-mini (fast, direct HTTP),
 * and pushes live translations to both the feed bar and the chat area.</p>
 */
@Component
public class AudioListeningTools {

    private static final Logger log = LoggerFactory.getLogger(AudioListeningTools.class);

    private static final int CAPTURE_SECONDS = 5;
    private static final int MAX_ROUNDS = 2160; // 3 hours at ~5s per round

    private final AudioMemoryService audioMemoryService;
    private final AsyncMessageService asyncMessages;
    private final ToolExecutionNotifier notifier;
    private final TtsTools ttsTools;

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url:https://api.openai.com}")
    private String baseUrl;

    private HttpClient httpClient;

    private volatile boolean listening = false;
    private volatile boolean vocalMode = false;
    private volatile Thread listenThread;

    /** Pending transcriptions for the UI feed. */
    private final ConcurrentLinkedQueue<String> listenFeed = new ConcurrentLinkedQueue<>();

    /** Most recent transcription — available for main AI context. */
    private volatile String latestTranscription = null;

    public AudioListeningTools(AudioMemoryService audioMemoryService,
                               AsyncMessageService asyncMessages, ToolExecutionNotifier notifier,
                               TtsTools ttsTools) {
        this.audioMemoryService = audioMemoryService;
        this.asyncMessages = asyncMessages;
        this.notifier = notifier;
        this.ttsTools = ttsTools;
    }

    @PostConstruct
    void init() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public boolean isListening() { return listening; }
    public boolean isVocalMode() { return vocalMode; }
    public void setVocalMode(boolean vocalMode) { this.vocalMode = vocalMode; }

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
            + "Captures what is playing through the speakers/headphones every 5 seconds, "
            + "translates to English, and shows live translations in the UI chat. "
            + "Use when the user says 'listen to what I'm listening', 'what song is this', "
            + "'listen to my meeting', or 'turn on your ears'. "
            + "Call stopListening() to stop.")
    public String startListening() {
        notifier.notify("Starting audio listening...");

        if (listening) {
            return "Already listening. Call stopListening() first to restart.";
        }

        log.info("[ListenMode] Starting — capture: {}s, max rounds: {}", CAPTURE_SECONDS, MAX_ROUNDS);

        listening = true;
        latestTranscription = null;

        listenThread = new Thread(this::runListenLoop, "audio-listen");
        listenThread.setDaemon(true);
        listenThread.start();

        return "Listening mode started. Capturing system audio every " + CAPTURE_SECONDS
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
     * Returns a TranslationResult with the English translation and "male"/"female"/"unknown".
     * If vocal mode is off, skips gender detection for speed.
     */
    private TranslationResult translateAndDetectGender(String text) {
        if (text == null || text.isBlank()) return new TranslationResult(text, "unknown");
        if (apiKey == null || apiKey.isBlank() || httpClient == null) return new TranslationResult(text, "unknown");

        try {
            String systemPrompt;
            if (vocalMode) {
                systemPrompt = "You are a translator. Translate the user's text to English and detect the speaker's likely gender from vocal context clues. "
                        + "Return ONLY a JSON object: {\"translation\":\"...\",\"gender\":\"male|female|unknown\"} "
                        + "If already English, return original text as translation. No extra text.";
            } else {
                systemPrompt = "You are a translator. Translate the user's text directly to English. "
                        + "If already English, return as-is. Output ONLY the translation. No quotes, no labels.";
            }

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

            // If vocal mode, parse JSON response; otherwise plain text
            if (vocalMode && content.contains("\"translation\"")) {
                String translation = extractJsonField(content, "translation");
                String gender = extractJsonField(content, "gender");
                if (translation == null || translation.isBlank()) translation = text;
                if (gender == null || gender.isBlank()) gender = "unknown";
                log.debug("[ListenMode] Translated+gender ({} → {} chars, gender={})",
                        text.length(), translation.length(), gender);
                return new TranslationResult(translation.trim(), gender.toLowerCase());
            } else {
                log.debug("[ListenMode] Translated ({} → {} chars)",
                        text.length(), content.length());
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

    // ═══ Listen loop ═══

    /**
     * Listen loop: capture 5s audio → fire off translation in background → immediately
     * start next capture. No idle gap between rounds — translation happens concurrently.
     */
    private void runListenLoop() {
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

                log.info("[ListenMode] Round {}/{} — capturing {}s audio...", round, MAX_ROUNDS, CAPTURE_SECONDS);

                try {
                    String transcription = audioMemoryService.captureNow(CAPTURE_SECONDS);

                    if (transcription != null && !transcription.isBlank()) {
                        consecutiveSilent = 0;
                        String display = transcription.length() > 300
                                ? transcription.substring(0, 300) + "..."
                                : transcription;

                        log.info("[ListenMode] Round {} — captured ({} chars), submitting translation",
                                round, transcription.length());

                        // Translate in background — don't block next capture
                        String captured = display;
                        translator.submit(() -> {
                            TranslationResult result = translateAndDetectGender(captured);
                            String translated = result.translation();

                            // Dedup: skip if same as the last translation (feedback artifact)
                            if (translated != null && translated.equals(latestTranscription)) {
                                log.debug("[ListenMode] Skipping duplicate translation");
                                return;
                            }

                            listenFeed.add(translated);
                            asyncMessages.push(translated);
                            latestTranscription = translated;

                            // Vocal mode: speak the translation with gender-matched voice
                            if (vocalMode && translated != null && !translated.isBlank()) {
                                ttsTools.speakAsyncWithVoice(translated, result.gender());
                            }
                        });
                    } else {
                        consecutiveSilent++;
                        log.debug("[ListenMode] Round {} — silence ({} consecutive)",
                                round, consecutiveSilent);

                        if (consecutiveSilent == 60) { // ~5 min at 5s captures
                            listenFeed.add("(no audio detected — is something playing?)");
                        }
                    }
                } catch (Exception e) {
                    log.warn("[ListenMode] Round {} — capture failed: {}", round, e.getMessage());
                }

                // No sleep — next capture starts immediately
                // (the 5s capture itself is the natural pacing)
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
