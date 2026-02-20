package com.botsfer;

import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Client for Signal Messenger via signal-cli-rest-api.
 * Requires a running signal-cli-rest-api instance.
 * See https://github.com/bbernhard/signal-cli-rest-api
 */
@Component
public class SignalApiClient {

    private final RestTemplate restTemplate;
    private final SignalConfig.SignalProperties properties;

    public SignalApiClient(RestTemplate restTemplate, SignalConfig.SignalProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    public boolean isConfigured() {
        return properties.isEnabled()
                && properties.getPhoneNumber() != null && !properties.getPhoneNumber().isBlank()
                && properties.getApiUrl() != null && !properties.getApiUrl().isBlank();
    }

    public Map<String, Object> sendMessage(String recipient, String text) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of(
                "message", text != null ? text : "",
                "number", properties.getPhoneNumber(),
                "recipients", List.of(recipient)
        );
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        String url = properties.getApiUrl() + "/v2/send";
        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, request, Map.class);
        return response.getBody() != null ? response.getBody() : Map.of();
    }
}
