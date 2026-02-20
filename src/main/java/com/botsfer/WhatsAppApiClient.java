package com.botsfer;

import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Client for WhatsApp Cloud API (Meta Business Platform).
 * See https://developers.facebook.com/docs/whatsapp/cloud-api
 */
@Component
public class WhatsAppApiClient {

    private static final String API_BASE = "https://graph.facebook.com/v21.0";

    private final RestTemplate restTemplate;
    private final WhatsAppConfig.WhatsAppProperties properties;

    public WhatsAppApiClient(RestTemplate restTemplate, WhatsAppConfig.WhatsAppProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    public boolean isConfigured() {
        return properties.isEnabled() && properties.getAccessToken() != null && !properties.getAccessToken().isBlank();
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(properties.getAccessToken());
        return headers;
    }

    public Map<String, Object> sendTextMessage(String to, String text) {
        Map<String, Object> body = Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "text",
                "text", Map.of("body", text != null ? text : "")
        );
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, authHeaders());
        ResponseEntity<Map> response = restTemplate.exchange(
                API_BASE + "/" + properties.getPhoneNumberId() + "/messages",
                HttpMethod.POST, request, Map.class);
        return response.getBody() != null ? response.getBody() : Map.of();
    }
}
