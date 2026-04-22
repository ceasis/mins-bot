package com.minsbot;

import com.minsbot.offline.OfflineModeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * ElevenLabs text-to-speech API. Returns audio bytes (WAV or MP3) for playback.
 * Includes a streaming variant that returns raw PCM for real-time playback.
 * See https://elevenlabs.io/docs/api-reference/text-to-speech/convert
 */
@Service
public class ElevenLabsVoiceService {

    private static final Logger log = LoggerFactory.getLogger(ElevenLabsVoiceService.class);
    private static final String BASE = "https://api.elevenlabs.io/v1/text-to-speech";

    private final RestTemplate restTemplate;
    private final ElevenLabsConfig.ElevenLabsProperties properties;
    private final HttpClient httpClient;

    @Autowired(required = false)
    private OfflineModeService offlineMode;

    public ElevenLabsVoiceService(RestTemplate restTemplate, ElevenLabsConfig.ElevenLabsProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        // Log resolved property values at construction time
        boolean hasKey = properties.getApiKey() != null && !properties.getApiKey().isBlank();
        boolean hasVoice = properties.getVoiceId() != null && !properties.getVoiceId().isBlank();
        log.info("[ElevenLabs] Init — enabled={}, hasApiKey={}, hasVoiceId={}, voiceId={}, model={}",
                properties.isEnabled(), hasKey, hasVoice,
                hasVoice ? properties.getVoiceId() : "(empty)",
                properties.getModelId());
    }

    public boolean isEnabled() {
        if (offlineMode != null && offlineMode.isOffline()) return false;
        return properties.isEnabled()
                && properties.getApiKey() != null && !properties.getApiKey().isBlank()
                && properties.getVoiceId() != null && !properties.getVoiceId().isBlank();
    }

    public String getVoiceId() { return properties.getVoiceId(); }
    public String getModelId() { return properties.getModelId(); }

    /**
     * Convert text to speech and return audio bytes (format per app.elevenlabs.output-format, e.g. WAV).
     *
     * @param text text to speak (max ~5000 chars per API)
     * @return audio bytes, or null on error
     */
    public byte[] textToSpeech(String text) {
        if (!isEnabled() || text == null || text.isBlank()) return null;
        String voiceId = properties.getVoiceId().trim();
        String url = BASE + "/" + voiceId + "?output_format=" + (properties.getOutputFormat() != null ? properties.getOutputFormat() : "wav_44100");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("xi-api-key", properties.getApiKey());
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_OCTET_STREAM));

        Map<String, Object> body = Map.of(
                "text", text.length() > 5000 ? text.substring(0, 5000) : text,
                "model_id", properties.getModelId() != null ? properties.getModelId() : "eleven_multilingual_v2"
        );

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        try {
            ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.POST, request, byte[].class);
            return response.getBody();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Streaming TTS — returns an InputStream of raw PCM bytes (24kHz 16-bit mono).
     * Uses the /stream endpoint with pcm_24000 output format for real-time playback
     * via SourceDataLine. Returns null on error. Caller must close the stream.
     */
    public InputStream textToSpeechStream(String text) {
        return textToSpeechStream(text, null);
    }

    /**
     * Streaming TTS with a specific voice ID override.
     * If voiceIdOverride is null or blank, uses the default configured voice.
     */
    public InputStream textToSpeechStream(String text, String voiceIdOverride) {
        if (!isEnabled() || text == null || text.isBlank()) return null;

        String voiceId = (voiceIdOverride != null && !voiceIdOverride.isBlank())
                ? voiceIdOverride.trim()
                : properties.getVoiceId().trim();
        String modelId = properties.getModelId() != null ? properties.getModelId() : "eleven_multilingual_v2";
        String url = BASE + "/" + voiceId + "/stream?output_format=pcm_24000";

        String input = text.length() > 5000 ? text.substring(0, 5000) : text;
        // Build JSON body manually — use concatenation (not formatted()) because user text may contain %
        String requestBody = "{\"text\":\"" + escapeJson(input) + "\",\"model_id\":\"" + escapeJson(modelId) + "\"}";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("xi-api-key", properties.getApiKey())
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<InputStream> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                try (InputStream err = response.body()) {
                    String body = new String(err.readAllBytes(), StandardCharsets.UTF_8);
                    log.warn("[ElevenLabs] Stream API returned HTTP {} — {}", response.statusCode(),
                            body.length() > 300 ? body.substring(0, 300) : body);
                }
                return null;
            }

            log.debug("[ElevenLabs] Streaming PCM audio started (voice={})", voiceId);
            return response.body();

        } catch (Exception e) {
            log.warn("[ElevenLabs] Stream failed: {}", e.getMessage());
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
