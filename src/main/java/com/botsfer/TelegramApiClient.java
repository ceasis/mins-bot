package com.botsfer;

import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Client for Telegram Bot API (setWebhook, sendMessage).
 * See https://core.telegram.org/bots/api
 */
@Component
public class TelegramApiClient {

    private static final String API_BASE = "https://api.telegram.org/bot";

    private final RestTemplate restTemplate;
    private final TelegramConfig.TelegramProperties properties;

    public TelegramApiClient(RestTemplate restTemplate, TelegramConfig.TelegramProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    public boolean isConfigured() {
        return properties.isEnabled() && properties.getBotToken() != null && !properties.getBotToken().isBlank();
    }

    private String apiUrl(String method) {
        return API_BASE + properties.getBotToken() + "/" + method;
    }

    public Map<String, Object> setWebhook(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(Map.of("url", url != null ? url : ""), headers);
        ResponseEntity<Map> response = restTemplate.exchange(apiUrl("setWebhook"), HttpMethod.POST, request, Map.class);
        return response.getBody() != null ? response.getBody() : Map.of();
    }

    public Map<String, Object> sendMessage(long chatId, String text) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of("chat_id", chatId, "text", text != null ? text : "");
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.exchange(apiUrl("sendMessage"), HttpMethod.POST, request, Map.class);
        return response.getBody() != null ? response.getBody() : Map.of();
    }
}
