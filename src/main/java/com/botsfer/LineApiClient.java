package com.botsfer;

import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Client for LINE Messaging API (reply, push messages).
 * See https://developers.line.biz/en/docs/messaging-api/
 */
@Component
public class LineApiClient {

    private static final String API_BASE = "https://api.line.me/v2/bot";

    private final RestTemplate restTemplate;
    private final LineConfig.LineProperties properties;

    public LineApiClient(RestTemplate restTemplate, LineConfig.LineProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    public boolean isConfigured() {
        return properties.isEnabled()
                && properties.getChannelAccessToken() != null
                && !properties.getChannelAccessToken().isBlank();
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(properties.getChannelAccessToken());
        return headers;
    }

    public Map<String, Object> replyMessage(String replyToken, String text) {
        Map<String, Object> body = Map.of(
                "replyToken", replyToken,
                "messages", List.of(Map.of("type", "text", "text", text != null ? text : ""))
        );
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, authHeaders());
        ResponseEntity<Map> response = restTemplate.exchange(
                API_BASE + "/message/reply",
                HttpMethod.POST, request, Map.class);
        return response.getBody() != null ? response.getBody() : Map.of();
    }

    public Map<String, Object> pushMessage(String userId, String text) {
        Map<String, Object> body = Map.of(
                "to", userId,
                "messages", List.of(Map.of("type", "text", "text", text != null ? text : ""))
        );
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, authHeaders());
        ResponseEntity<Map> response = restTemplate.exchange(
                API_BASE + "/message/push",
                HttpMethod.POST, request, Map.class);
        return response.getBody() != null ? response.getBody() : Map.of();
    }
}
