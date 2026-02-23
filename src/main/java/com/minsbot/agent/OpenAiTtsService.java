package com.minsbot.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Text-to-speech using OpenAI's /v1/audio/speech endpoint.
 * Returns MP3 bytes. Reuses the same API key as chat/vision.
 *
 * <p>Voices: alloy, echo, fable, nova, onyx, shimmer.
 * Models: tts-1 (fast), tts-1-hd (higher quality).
 */
@Component
public class OpenAiTtsService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiTtsService.class);

    @Value("${app.openai.api-key:}")
    private String apiKey;

    @Value("${app.openai.base-url:https://api.openai.com/v1}")
    private String baseUrl;

    private volatile String model = "tts-1";
    private volatile String voice = "nova";
    private volatile double speed = 1.0;

    private HttpClient httpClient;

    @PostConstruct
    void init() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        if (apiKey == null || apiKey.isBlank()) {
            log.info("[OpenAiTTS] No API key — TTS disabled");
        } else {
            log.info("[OpenAiTTS] Ready (model={}, voice={}, speed={})", model, voice, speed);
        }
    }

    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank() && httpClient != null;
    }

    public void setVoice(String voice) { this.voice = voice; }
    public void setSpeed(double speed) { this.speed = Math.max(0.25, Math.min(4.0, speed)); }
    public void setModel(String model) { this.model = model; }

    public String getVoice() { return voice; }
    public double getSpeed() { return speed; }

    /**
     * Convert text to speech. Returns MP3 audio bytes, or null on failure.
     *
     * @param text text to speak (max ~4096 chars per API call)
     * @return MP3 bytes, or null on error
     */
    public byte[] textToSpeech(String text) {
        if (!isAvailable() || text == null || text.isBlank()) return null;

        String input = text.length() > 4096 ? text.substring(0, 4096) : text;

        // Request PCM format — raw 24kHz 16-bit mono little-endian PCM.
        // TtsTools wraps with a WAV header in Java (no ffmpeg conversion needed → faster).
        // Use concatenation instead of formatted() — user text may contain % which breaks format strings.
        String requestBody = "{\"model\":\"" + escapeJson(model)
                + "\",\"voice\":\"" + escapeJson(voice)
                + "\",\"input\":\"" + escapeJson(input)
                + "\",\"speed\":" + String.format("%.2f", speed)
                + ",\"response_format\":\"pcm\"}";

        String url = baseUrl.endsWith("/") ? baseUrl + "audio/speech" : baseUrl + "/audio/speech";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                String body = new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
                log.warn("[OpenAiTTS] API returned HTTP {} — {}", response.statusCode(),
                        body.length() > 300 ? body.substring(0, 300) : body);
                return null;
            }

            byte[] audio = response.body();
            if (audio == null || audio.length == 0) {
                log.warn("[OpenAiTTS] Empty response from API");
                return null;
            }

            log.info("[OpenAiTTS] Generated {} bytes of PCM audio", audio.length);
            return audio;

        } catch (Exception e) {
            log.warn("[OpenAiTTS] Failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Streaming variant — returns an InputStream of raw PCM bytes (24kHz 16-bit mono).
     * The caller can read chunks and play them via SourceDataLine for near-instant playback.
     * Returns null on error. Caller must close the stream.
     */
    public InputStream textToSpeechStream(String text) {
        if (!isAvailable() || text == null || text.isBlank()) return null;

        String input = text.length() > 4096 ? text.substring(0, 4096) : text;

        String requestBody = "{\"model\":\"" + escapeJson(model)
                + "\",\"voice\":\"" + escapeJson(voice)
                + "\",\"input\":\"" + escapeJson(input)
                + "\",\"speed\":" + String.format("%.2f", speed)
                + ",\"response_format\":\"pcm\"}";

        String url = baseUrl.endsWith("/") ? baseUrl + "audio/speech" : baseUrl + "/audio/speech";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<InputStream> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                try (InputStream err = response.body()) {
                    String body = new String(err.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    log.warn("[OpenAiTTS] Stream API returned HTTP {} — {}", response.statusCode(),
                            body.length() > 300 ? body.substring(0, 300) : body);
                }
                return null;
            }

            log.debug("[OpenAiTTS] Streaming PCM audio started");
            return response.body();

        } catch (Exception e) {
            log.warn("[OpenAiTTS] Stream failed: {}", e.getMessage());
            return null;
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
