package com.botsfer;

import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Client for Discord Bot API (send channel messages, interaction responses).
 * See https://discord.com/developers/docs/intro
 */
@Component
public class DiscordApiClient {

    private static final String API_BASE = "https://discord.com/api/v10";

    private final RestTemplate restTemplate;
    private final DiscordConfig.DiscordProperties properties;

    public DiscordApiClient(RestTemplate restTemplate, DiscordConfig.DiscordProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    public boolean isConfigured() {
        return properties.isEnabled() && properties.getBotToken() != null && !properties.getBotToken().isBlank();
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bot " + properties.getBotToken());
        return headers;
    }

    public Map<String, Object> sendMessage(String channelId, String content) {
        Map<String, Object> body = Map.of("content", content != null ? content : "");
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, authHeaders());
        ResponseEntity<Map> response = restTemplate.exchange(
                API_BASE + "/channels/" + channelId + "/messages",
                HttpMethod.POST, request, Map.class);
        return response.getBody() != null ? response.getBody() : Map.of();
    }

    public String getPublicKey() {
        return properties.getPublicKey();
    }
}
