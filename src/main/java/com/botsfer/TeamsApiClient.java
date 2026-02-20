package com.botsfer;

import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Client for Microsoft Teams Bot Framework (OAuth token + reply to activity).
 * See https://learn.microsoft.com/en-us/azure/bot-service/rest-api/bot-framework-rest-connector-send-and-receive-messages
 */
@Component
public class TeamsApiClient {

    private static final String TOKEN_URL = "https://login.microsoftonline.com/botframework.com/oauth2/v2.0/token";

    private final RestTemplate restTemplate;
    private final TeamsConfig.TeamsProperties properties;

    private String cachedToken;
    private long tokenExpiresAt;

    public TeamsApiClient(RestTemplate restTemplate, TeamsConfig.TeamsProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    public boolean isConfigured() {
        return properties.isEnabled()
                && properties.getAppId() != null && !properties.getAppId().isBlank()
                && properties.getAppPassword() != null && !properties.getAppPassword().isBlank();
    }

    @SuppressWarnings("unchecked")
    private synchronized String getAccessToken() {
        if (cachedToken != null && System.currentTimeMillis() < tokenExpiresAt) {
            return cachedToken;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", properties.getAppId());
        form.add("client_secret", properties.getAppPassword());
        form.add("scope", "https://api.botframework.com/.default");
        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(form, headers);
        ResponseEntity<Map> response = restTemplate.exchange(TOKEN_URL, HttpMethod.POST, request, Map.class);
        Map<String, Object> body = response.getBody();
        if (body != null) {
            cachedToken = (String) body.get("access_token");
            Number expiresIn = (Number) body.get("expires_in");
            tokenExpiresAt = System.currentTimeMillis() + (expiresIn != null ? expiresIn.longValue() * 1000 - 60000 : 0);
        }
        return cachedToken;
    }

    public Map<String, Object> replyToActivity(String serviceUrl, String conversationId, String activityId, String text) {
        String token = getAccessToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        Map<String, Object> body = Map.of("type", "message", "text", text != null ? text : "");
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        String url = serviceUrl + "/v3/conversations/" + conversationId + "/activities/" + activityId;
        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, request, Map.class);
        return response.getBody() != null ? response.getBody() : Map.of();
    }
}
