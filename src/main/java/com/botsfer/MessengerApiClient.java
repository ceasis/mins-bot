package com.botsfer;

import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Client for Facebook Messenger Platform Send API.
 * See https://developers.facebook.com/docs/messenger-platform/send-messages
 */
@Component
public class MessengerApiClient {

    private static final String API_BASE = "https://graph.facebook.com/v21.0/me/messages";

    private final RestTemplate restTemplate;
    private final MessengerConfig.MessengerProperties properties;

    public MessengerApiClient(RestTemplate restTemplate, MessengerConfig.MessengerProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    public boolean isConfigured() {
        return properties.isEnabled()
                && properties.getPageAccessToken() != null
                && !properties.getPageAccessToken().isBlank();
    }

    public Map<String, Object> sendTextMessage(String recipientId, String text) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(properties.getPageAccessToken());

        Map<String, Object> body = Map.of(
                "recipient", Map.of("id", recipientId),
                "message", Map.of("text", text != null ? text : "")
        );
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.exchange(API_BASE, HttpMethod.POST, request, Map.class);
        return response.getBody() != null ? response.getBody() : Map.of();
    }
}
