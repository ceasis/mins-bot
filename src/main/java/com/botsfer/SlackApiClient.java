package com.botsfer;

import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Client for Slack Web API (chat.postMessage).
 * See https://api.slack.com/methods/chat.postMessage
 */
@Component
public class SlackApiClient {

    private static final String API_BASE = "https://slack.com/api";

    private final RestTemplate restTemplate;
    private final SlackConfig.SlackProperties properties;

    public SlackApiClient(RestTemplate restTemplate, SlackConfig.SlackProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    public boolean isConfigured() {
        return properties.isEnabled() && properties.getBotToken() != null && !properties.getBotToken().isBlank();
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(properties.getBotToken());
        return headers;
    }

    public Map<String, Object> postMessage(String channel, String text) {
        Map<String, Object> body = Map.of("channel", channel, "text", text != null ? text : "");
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, authHeaders());
        ResponseEntity<Map> response = restTemplate.exchange(
                API_BASE + "/chat.postMessage",
                HttpMethod.POST, request, Map.class);
        return response.getBody() != null ? response.getBody() : Map.of();
    }
}
