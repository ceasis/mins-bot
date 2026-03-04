package com.minsbot.agent;

import com.google.genai.Client;
import com.google.genai.AsyncSession;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.LiveConnectConfig;
import com.google.genai.types.LiveSendRealtimeInputParameters;
import com.google.genai.types.LiveServerMessage;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.Modality;
import com.google.genai.types.Part;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Gemini Live API client using the official google-genai SDK.
 * Streams audio via WebSocket and receives real-time text translations.
 */
@Service
public class GeminiLiveService {

    private static final Logger log = LoggerFactory.getLogger(GeminiLiveService.class);

    private static final Duration MAX_SESSION = Duration.ofMinutes(14);
    private static final int CONNECT_TIMEOUT_SECONDS = 15;

    @Value("${app.gemini.api-key:}")
    private String apiKey;

    @Value("${app.gemini-live.model:gemini-2.5-flash-native-audio-latest}")
    private volatile String liveModel;

    @Value("${app.gemini-live.enabled:true}")
    private boolean enabled;

    @Value("${app.gemini-live.source-language:Filipino/Tagalog}")
    private String sourceLanguage;

    private volatile Client client;
    private volatile AsyncSession session;
    private volatile boolean connected;
    private volatile Instant sessionStartTime;
    private volatile LiveTranscriptionListener listener;

    /** Change model for next session (takes effect on next connect/reconnect). */
    public void setLiveModel(String model) {
        if (model != null && !model.isBlank()) {
            this.liveModel = model;
            log.info("[GeminiLive] Model changed to: {}", model);
        }
    }

    public String getLiveModel() { return liveModel; }

    // ── Callback interface ──

    public interface LiveTranscriptionListener {
        void onTurnComplete(String inputTranscription, String translation);
        void onError(String error);
    }

    // ── Public API ──

    public boolean isAvailable() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    public boolean isConnected() {
        return connected && session != null;
    }

    public boolean needsReconnect() {
        return connected && sessionStartTime != null
                && Duration.between(sessionStartTime, Instant.now()).compareTo(MAX_SESSION) > 0;
    }

    /**
     * Open a live session via the google-genai SDK.
     * Blocks until connected or times out.
     */
    public void connect(LiveTranscriptionListener listener) {
        if (!isAvailable()) {
            throw new IllegalStateException("Gemini Live not available (API key missing or disabled)");
        }

        this.listener = listener;
        log.info("[GeminiLive] Connecting via SDK (model={})...", liveModel);

        try {
            // Create SDK client with v1beta API version
            HttpOptions httpOptions = HttpOptions.builder()
                    .apiVersion("v1beta")
                    .build();
            client = Client.builder().apiKey(apiKey).httpOptions(httpOptions).build();

            // Build system instruction
            String systemInstruction = "You are a real-time translator for " + sourceLanguage + " to English. "
                    + "The audio you will hear is in " + sourceLanguage + ". "
                    + "Listen carefully and provide accurate, natural English translations. "
                    + "Capture the full meaning, context, and intent of what is being said. "
                    + "IMPORTANT: Always prefix your response with a gender tag based on the speaker's voice: "
                    + "[M] for male, [F] for female, [U] if unknown. "
                    + "Examples: [F] My dream is to become a game developer. "
                    + "[M] That sounds great, good luck! "
                    + "[U] The weather is nice today. "
                    + "If the audio is already in English, still translate/repeat it with the gender prefix. "
                    + "Output ONLY the gender tag followed by the English translation. No other text.";

            Content sysContent = Content.builder()
                    .parts(List.of(Part.builder().text(systemInstruction).build()))
                    .build();

            LiveConnectConfig config = LiveConnectConfig.builder()
                    .responseModalities(List.of(new Modality(Modality.Known.TEXT)))
                    .systemInstruction(sysContent)
                    .build();

            // Connect asynchronously, block until done
            CountDownLatch connectLatch = new CountDownLatch(1);
            final Exception[] connectError = {null};

            client.async.live.connect(liveModel, config)
                    .thenAccept(asyncSession -> {
                        session = asyncSession;
                        connected = true;
                        sessionStartTime = Instant.now();
                        log.info("[GeminiLive] Connected (sessionId={})", asyncSession.sessionId());

                        // Register message receiver
                        asyncSession.receive(msg -> handleServerMessage(msg))
                                .exceptionally(ex -> {
                                    log.warn("[GeminiLive] Receive error: {}", ex.getMessage());
                                    connected = false;
                                    if (listener != null) listener.onError(ex.getMessage());
                                    return null;
                                });

                        connectLatch.countDown();
                    })
                    .exceptionally(ex -> {
                        connectError[0] = new RuntimeException(ex.getMessage(), ex);
                        connectLatch.countDown();
                        return null;
                    });

            if (!connectLatch.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new RuntimeException("Gemini Live connect timed out after " + CONNECT_TIMEOUT_SECONDS + "s");
            }

            if (connectError[0] != null) {
                throw connectError[0];
            }

            log.info("[GeminiLive] Setup complete, ready for audio");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Gemini Live connect interrupted", e);
        } catch (Exception e) {
            connected = false;
            log.error("[GeminiLive] Connection failed: {}", e.getMessage());
            throw new RuntimeException("Gemini Live connection failed: " + e.getMessage(), e);
        }
    }

    /**
     * Send raw 16kHz mono 16-bit PCM audio to Gemini via the SDK.
     * Auto-reconnects if session is nearing the 15-minute limit.
     */
    public void sendAudio(byte[] pcmData) {
        if (!connected || session == null || pcmData == null || pcmData.length == 0) return;

        // Auto-reconnect before session expires
        if (needsReconnect()) {
            log.info("[GeminiLive] Session nearing 15-min limit, reconnecting...");
            reconnect();
        }

        try {
            Blob audioBlob = Blob.builder()
                    .data(pcmData)
                    .mimeType("audio/pcm;rate=16000")
                    .build();

            LiveSendRealtimeInputParameters params = LiveSendRealtimeInputParameters.builder()
                    .audio(audioBlob)
                    .build();

            session.sendRealtimeInput(params)
                    .exceptionally(ex -> {
                        log.warn("[GeminiLive] sendAudio failed: {}", ex.getMessage());
                        connected = false;
                        return null;
                    });
        } catch (Exception e) {
            log.warn("[GeminiLive] sendAudio failed: {}", e.getMessage());
            connected = false;
        }
    }

    /**
     * Gracefully close the live session.
     */
    public void disconnect() {
        connected = false;
        if (session != null) {
            try {
                session.close().get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
            session = null;
        }
        client = null;
        log.info("[GeminiLive] Disconnected");
    }

    // ── Internal ──

    private void reconnect() {
        LiveTranscriptionListener savedListener = this.listener;
        try { disconnect(); } catch (Exception ignored) {}
        try { Thread.sleep(500); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        connect(savedListener);
    }

    // Accumulate model text across messages within a turn
    private final StringBuilder accModelText = new StringBuilder();
    private String accInputTranscription = null;

    private void handleServerMessage(LiveServerMessage msg) {
        // Check for goAway
        if (msg.goAway().isPresent()) {
            log.info("[GeminiLive] Server sent goAway — will reconnect on next sendAudio");
            connected = false;
            return;
        }

        // Check for setupComplete
        if (msg.setupComplete().isPresent()) {
            log.debug("[GeminiLive] Setup acknowledged");
            return;
        }

        // Process serverContent
        msg.serverContent().ifPresent(content -> {
            // Extract input transcription
            content.inputTranscription().ifPresent(transcription -> {
                // Transcription object — try to get text
                String text = transcription.toJson();
                if (text != null && text.contains("\"text\"")) {
                    String extracted = extractJsonTextField(text);
                    if (extracted != null) accInputTranscription = extracted;
                }
            });

            // Extract model turn text
            content.modelTurn().ifPresent(modelContent -> {
                modelContent.parts().ifPresent(parts -> {
                    for (Part part : parts) {
                        part.text().ifPresent(text -> {
                            if (!text.isBlank()) accModelText.append(text);
                        });
                    }
                });
            });

            // Check if turn is complete
            boolean turnDone = content.turnComplete().orElse(false);
            if (turnDone && listener != null) {
                String fullModel = accModelText.length() > 0 ? accModelText.toString() : null;
                String fullInput = accInputTranscription;
                accModelText.setLength(0);
                accInputTranscription = null;

                String translation = fullModel != null ? fullModel : fullInput;
                if (translation != null && !translation.isBlank()) {
                    listener.onTurnComplete(fullInput, translation);
                }
            }
        });
    }

    /** Extract the "text" field from a simple JSON string. */
    private String extractJsonTextField(String json) {
        int idx = json.indexOf("\"text\"");
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + 6);
        if (colon < 0) return null;
        int start = json.indexOf('"', colon + 1);
        if (start < 0) return null;
        start++;
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                if (next == '"') sb.append('"');
                else if (next == '\\') sb.append('\\');
                else if (next == 'n') sb.append('\n');
                else sb.append(next);
                i++;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        String result = sb.toString().trim();
        return result.isEmpty() ? null : result;
    }
}
