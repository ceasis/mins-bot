package com.botsfer;

import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Client for Viber REST Bot API (set_webhook, send_message).
 * See https://developers.viber.com/docs/api/rest-bot-api/
 */
@Component
public class ViberApiClient {

    private static final String VIBER_API_BASE = "https://chatapi.viber.com/pa";

    private final RestTemplate restTemplate;
    private final ViberConfig.ViberProperties properties;

    public ViberApiClient(RestTemplate restTemplate, ViberConfig.ViberProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    public boolean isConfigured() {
        return properties.isEnabled() && properties.getAuthToken() != null && !properties.getAuthToken().isBlank();
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Viber-Auth-Token", properties.getAuthToken());
        return headers;
    }

    /**
     * Set the webhook URL for the bot. Call once with your public HTTPS URL.
     * Viber will send a verification request to this URL; your endpoint must return 200.
     */
    public Map<String, Object> setWebhook(String url) {
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(
                Map.of("url", url != null ? url : ""),
                authHeaders()
        );
        ResponseEntity<Map> response = restTemplate.exchange(
                VIBER_API_BASE + "/set_webhook",
                HttpMethod.POST,
                request,
                Map.class
        );
        return response.getBody() != null ? response.getBody() : Map.of();
    }

    /**
     * Send a text message to a Viber user (receiver id from webhook callback).
     */
    public Map<String, Object> sendTextMessage(String receiverId, String text) {
        Map<String, Object> body = Map.of(
                "receiver", receiverId,
                "min_api_version", 1,
                "sender", Map.of("name", properties.getBotName()),
                "type", "text",
                "text", text != null ? text : ""
        );
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, authHeaders());
        ResponseEntity<Map> response = restTemplate.exchange(
                VIBER_API_BASE + "/send_message",
                HttpMethod.POST,
                request,
                Map.class
        );
        return response.getBody() != null ? response.getBody() : Map.of();
    }
}
