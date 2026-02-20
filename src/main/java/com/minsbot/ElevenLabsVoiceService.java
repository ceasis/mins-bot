package com.minsbot;

import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * ElevenLabs text-to-speech API. Returns audio bytes (WAV or MP3) for playback.
 * See https://elevenlabs.io/docs/api-reference/text-to-speech/convert
 */
@Service
public class ElevenLabsVoiceService {

    private static final String BASE = "https://api.elevenlabs.io/v1/text-to-speech";

    private final RestTemplate restTemplate;
    private final ElevenLabsConfig.ElevenLabsProperties properties;

    public ElevenLabsVoiceService(RestTemplate restTemplate, ElevenLabsConfig.ElevenLabsProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    public boolean isEnabled() {
        return properties.isEnabled()
                && properties.getApiKey() != null && !properties.getApiKey().isBlank()
                && properties.getVoiceId() != null && !properties.getVoiceId().isBlank();
    }

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
}
