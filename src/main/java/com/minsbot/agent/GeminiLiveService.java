package com.minsbot.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Gemini Live API client — streams audio via WebSocket and receives real-time
 * transcription + translation. Uses Java 11 built-in WebSocket (no external deps).
 *
 * Protocol: wss://generativelanguage.googleapis.com/.../BidiGenerateContent
 * - Send setup message (model, system instruction, TEXT response modality)
 * - Stream audio as base64 PCM chunks via realtimeInput
 * - Receive serverContent with inputTranscription + modelTurn text
 */
@Service
public class GeminiLiveService {

    private static final Logger log = LoggerFactory.getLogger(GeminiLiveService.class);

    private static final String WS_URL_TEMPLATE =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=%s";

    private static final Duration MAX_SESSION = Duration.ofMinutes(14); // reconnect before 15-min limit
    private static final int SETUP_TIMEOUT_SECONDS = 10;

    @Value("${app.gemini.api-key:}")
    private String apiKey;

    @Value("${app.gemini-live.model:gemini-2.0-flash-exp}")
    private String liveModel;

    @Value("${app.gemini-live.enabled:true}")
    private boolean enabled;

    @Value("${app.gemini-live.source-language:Filipino/Tagalog}")
    private String sourceLanguage;

    private volatile WebSocket webSocket;
    private volatile boolean connected;
    private volatile boolean setupComplete;
    private volatile Instant sessionStartTime;
    private volatile LiveTranscriptionListener listener;
    private volatile CountDownLatch setupLatch;

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
        return connected && webSocket != null;
    }

    public boolean needsReconnect() {
        return connected && sessionStartTime != null
                && Duration.between(sessionStartTime, Instant.now()).compareTo(MAX_SESSION) > 0;
    }

    /**
     * Open WebSocket connection to Gemini Live API and send setup message.
     * Blocks until setup is acknowledged or times out.
     */
    public void connect(LiveTranscriptionListener listener) {
        if (!isAvailable()) {
            throw new IllegalStateException("Gemini Live not available (API key missing or disabled)");
        }

        this.listener = listener;
        this.setupComplete = false;
        this.setupLatch = new CountDownLatch(1);

        String url = String.format(WS_URL_TEMPLATE, apiKey);
        log.info("[GeminiLive] Connecting to WebSocket (model={})...", liveModel);

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            webSocket = client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(url), new GeminiWebSocketListener())
                    .join();

            // Send setup message
            sendSetupMessage();

            // Wait for setupComplete acknowledgment
            if (!setupLatch.await(SETUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("[GeminiLive] Setup timed out after {}s", SETUP_TIMEOUT_SECONDS);
                disconnect();
                throw new RuntimeException("Gemini Live setup timed out");
            }

            connected = true;
            sessionStartTime = Instant.now();
            log.info("[GeminiLive] Connected and setup complete");

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
     * Send raw 16kHz mono 16-bit PCM audio to Gemini via WebSocket.
     * Auto-reconnects if session is nearing the 15-minute limit.
     */
    public void sendAudio(byte[] pcmData) {
        if (!connected || pcmData == null || pcmData.length == 0) return;

        // Auto-reconnect before session expires
        if (needsReconnect()) {
            log.info("[GeminiLive] Session nearing 15-min limit, reconnecting...");
            reconnect();
        }

        String base64 = Base64.getEncoder().encodeToString(pcmData);
        String msg = "{\"realtimeInput\":{\"mediaChunks\":[{"
                + "\"mimeType\":\"audio/pcm;rate=16000\","
                + "\"data\":\"" + base64 + "\""
                + "}]}}";

        try {
            webSocket.sendText(msg, true);
        } catch (Exception e) {
            log.warn("[GeminiLive] sendAudio failed: {}", e.getMessage());
            connected = false;
        }
    }

    /**
     * Gracefully close the WebSocket connection.
     */
    public void disconnect() {
        connected = false;
        setupComplete = false;
        if (webSocket != null) {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            } catch (Exception ignored) {}
            webSocket = null;
        }
        log.info("[GeminiLive] Disconnected");
    }

    // ── Internal ──

    private void sendSetupMessage() {
        String systemInstruction = "You are a real-time translator for " + sourceLanguage + " to English. "
                + "The audio you will hear is in " + sourceLanguage + ". "
                + "Listen carefully and provide accurate, natural English translations — not word-by-word literal translations. "
                + "Capture the full meaning, context, and intent of what is being said. "
                + "1) Transcribe what you hear in " + sourceLanguage + ". "
                + "2) Translate it naturally to English, preserving meaning and nuance. "
                + "3) Detect the speaker gender (male/female/unknown). "
                + "Return ONLY a JSON object: "
                + "{\\\"original\\\":\\\"...\\\",\\\"translation\\\":\\\"...\\\",\\\"gender\\\":\\\"male|female|unknown\\\"}. "
                + "If the audio is already in English, set original and translation to the same text. No extra text.";

        String setup = "{\"setup\":{"
                + "\"model\":\"models/" + liveModel + "\","
                + "\"system_instruction\":{\"parts\":[{\"text\":\"" + systemInstruction + "\"}]},"
                + "\"generation_config\":{"
                + "\"responseModalities\":[\"TEXT\"],"
                + "\"inputAudioTranscription\":{}"
                + "}"
                + "}}";

        log.debug("[GeminiLive] Sending setup: model={}", liveModel);
        webSocket.sendText(setup, true);
    }

    private void reconnect() {
        LiveTranscriptionListener savedListener = this.listener;
        try { disconnect(); } catch (Exception ignored) {}
        try { Thread.sleep(500); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        connect(savedListener);
    }

    private void handleServerMessage(String json) {
        log.debug("[GeminiLive] Received: {}", json.length() > 200 ? json.substring(0, 200) + "..." : json);

        // Check for setupComplete
        if (json.contains("\"setupComplete\"")) {
            setupComplete = true;
            if (setupLatch != null) setupLatch.countDown();
            log.debug("[GeminiLive] Setup acknowledged");
            return;
        }

        // Check for goAway (server closing)
        if (json.contains("\"goAway\"")) {
            log.info("[GeminiLive] Server sent goAway — will reconnect on next sendAudio");
            connected = false;
            return;
        }

        // Parse serverContent
        if (!json.contains("\"serverContent\"")) return;

        String inputTranscription = null;
        String modelText = null;
        boolean turnComplete = json.contains("\"turnComplete\":true")
                || json.contains("\"turnComplete\": true");

        // Extract inputTranscription.text
        int itIdx = json.indexOf("\"inputTranscription\"");
        if (itIdx >= 0) {
            inputTranscription = extractNestedText(json, itIdx);
        }

        // Extract modelTurn.parts[].text
        int mtIdx = json.indexOf("\"modelTurn\"");
        if (mtIdx >= 0) {
            modelText = extractNestedText(json, mtIdx);
        }

        // Dispatch on turn complete
        if (turnComplete && listener != null) {
            String translation = modelText != null ? modelText : inputTranscription;
            if (translation != null && !translation.isBlank()) {
                listener.onTurnComplete(inputTranscription, translation);
            }
        }
    }

    /**
     * Extract the "text" field value from a JSON substring starting at offset.
     * Simple manual parser — looks for "text":"..." after the given offset.
     */
    private String extractNestedText(String json, int startOffset) {
        int textKeyIdx = json.indexOf("\"text\"", startOffset);
        if (textKeyIdx < 0) return null;

        // Find the colon after "text"
        int colonIdx = json.indexOf(':', textKeyIdx + 6);
        if (colonIdx < 0) return null;

        // Find opening quote
        int openQuote = json.indexOf('"', colonIdx + 1);
        if (openQuote < 0) return null;

        // Find closing quote (handle escaped quotes)
        StringBuilder sb = new StringBuilder();
        for (int i = openQuote + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    default: sb.append('\\').append(next); break;
                }
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

    // ── WebSocket listener ──

    private class GeminiWebSocketListener implements WebSocket.Listener {

        private final StringBuilder textBuffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            log.debug("[GeminiLive] WebSocket opened");
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                try {
                    handleServerMessage(textBuffer.toString());
                } catch (Exception e) {
                    log.warn("[GeminiLive] Error handling message: {}", e.getMessage());
                }
                textBuffer.setLength(0);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            connected = false;
            log.info("[GeminiLive] WebSocket closed: {} {}", statusCode, reason);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            connected = false;
            log.warn("[GeminiLive] WebSocket error: {}", error.getMessage());
            if (listener != null) listener.onError(error.getMessage());
        }
    }
}
