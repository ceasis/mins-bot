package com.minsbot;

import com.minsbot.offline.OfflineModeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Fish Audio text-to-speech API. Returns streaming PCM audio for real-time playback.
 * See https://docs.fish.audio
 *
 * <p>Respects {@link OfflineModeService} — when offline mode is on, {@link #isEnabled()}
 * returns false so callers fall back to local Windows SAPI / Piper. This is non-negotiable
 * for the "nothing leaves this machine" guarantee we sell offline mode on.
 */
@Service
public class FishAudioVoiceService {

    private static final Logger log = LoggerFactory.getLogger(FishAudioVoiceService.class);
    private static final String TTS_URL = "https://api.fish.audio/v1/tts";

    private final FishAudioConfig.FishAudioProperties properties;
    private final HttpClient httpClient;

    @Autowired(required = false)
    private OfflineModeService offlineMode;

    public FishAudioVoiceService(FishAudioConfig.FishAudioProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        boolean hasKey = properties.getApiKey() != null && !properties.getApiKey().isBlank();
        String rid = properties.getReferenceId();
        log.info("[FishAudio] Init — enabled={}, hasApiKey={}, model={}, referenceId={}",
                properties.isEnabled(), hasKey, properties.getModel(),
                rid == null || rid.isBlank() ? "(none — set fish.audio.reference.id)" : rid);
    }

    public boolean isEnabled() {
        if (offlineMode != null && offlineMode.isOffline()) return false;
        return properties.isEnabled()
                && properties.getApiKey() != null && !properties.getApiKey().isBlank();
    }

    public String getReferenceId() { return properties.getReferenceId(); }
    public String getModel() { return properties.getModel(); }

    /**
     * Streaming TTS — returns an InputStream of raw PCM bytes (24kHz 16-bit mono).
     * Returns null on error. Caller must close the stream.
     */
    public InputStream textToSpeechStream(String text) {
        return textToSpeechStream(text, null);
    }

    /**
     * Streaming TTS with a specific reference ID override for voice selection.
     * If referenceIdOverride is null or blank, uses the default configured reference.
     */
    public InputStream textToSpeechStream(String text, String referenceIdOverride) {
        if (!isEnabled() || text == null || text.isBlank()) return null;

        String refId = (referenceIdOverride != null && !referenceIdOverride.isBlank())
                ? referenceIdOverride.trim()
                : (properties.getReferenceId() != null ? properties.getReferenceId().trim() : "");

        String input = text.length() > 5000 ? text.substring(0, 5000) : text;

        StringBuilder json = new StringBuilder();
        json.append("{\"text\":\"").append(escapeJson(input)).append("\"");
        if (!refId.isBlank()) {
            json.append(",\"reference_id\":\"").append(escapeJson(refId)).append("\"");
        }
        int sr = properties.getSampleRate() > 0 ? properties.getSampleRate() : 24000;
        json.append(",\"format\":\"pcm\"");
        json.append(",\"sample_rate\":").append(sr);
        json.append(",\"latency\":\"normal\"");
        appendProsodyAndNormalize(json);
        json.append("}");

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TTS_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .header("model", properties.getModel() != null ? properties.getModel() : "s2-pro")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(json.toString()))
                    .build();

            HttpResponse<InputStream> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                try (InputStream err = response.body()) {
                    String body = new String(err.readAllBytes(), StandardCharsets.UTF_8);
                    log.warn("[FishAudio] TTS API returned HTTP {} — {}", response.statusCode(),
                            body.length() > 300 ? body.substring(0, 300) : body);
                }
                return null;
            }

            log.debug("[FishAudio] Streaming PCM audio started (ref={})", refId.isBlank() ? "default" : refId);
            return response.body();

        } catch (Exception e) {
            log.warn("[FishAudio] Stream failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Non-streaming TTS — returns complete audio bytes. Uses the configured output format.
     *
     * @param text text to speak (max ~5000 chars)
     * @return audio bytes, or null on error
     */
    public byte[] textToSpeech(String text) {
        if (!isEnabled() || text == null || text.isBlank()) return null;

        String refId = properties.getReferenceId() != null ? properties.getReferenceId().trim() : "";
        String input = text.length() > 5000 ? text.substring(0, 5000) : text;
        String fmt = properties.getFormat() != null ? properties.getFormat() : "wav";

        StringBuilder json = new StringBuilder();
        json.append("{\"text\":\"").append(escapeJson(input)).append("\"");
        if (!refId.isBlank()) {
            json.append(",\"reference_id\":\"").append(escapeJson(refId)).append("\"");
        }
        json.append(",\"format\":\"").append(escapeJson(fmt)).append("\"");
        if ("pcm".equalsIgnoreCase(fmt)) {
            int sr = properties.getSampleRate() > 0 ? properties.getSampleRate() : 24000;
            json.append(",\"sample_rate\":").append(sr);
        }
        appendProsodyAndNormalize(json);
        json.append("}");

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TTS_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .header("model", properties.getModel() != null ? properties.getModel() : "s2-pro")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(json.toString()))
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                log.warn("[FishAudio] TTS API returned HTTP {}", response.statusCode());
                return null;
            }

            return response.body();

        } catch (Exception e) {
            log.warn("[FishAudio] TTS failed: {}", e.getMessage());
            return null;
        }
    }

    /** Appends {@code prosody} / {@code normalize} when configured (matches Fish web UI sliders). */
    private void appendProsodyAndNormalize(StringBuilder json) {
        Double speed = properties.getProsodySpeed();
        Double volume = properties.getProsodyVolume();
        Boolean normLoud = properties.getNormalizeLoudness();
        if (speed != null || volume != null || normLoud != null) {
            json.append(",\"prosody\":{");
            boolean comma = false;
            if (speed != null) {
                json.append("\"speed\":").append(speed);
                comma = true;
            }
            if (volume != null) {
                if (comma) json.append(',');
                json.append("\"volume\":").append(volume);
                comma = true;
            }
            if (normLoud != null) {
                if (comma) json.append(',');
                json.append("\"normalize_loudness\":").append(normLoud);
            }
            json.append('}');
        }
        Boolean normText = properties.getNormalizeText();
        if (normText != null) {
            json.append(",\"normalize\":").append(normText);
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
