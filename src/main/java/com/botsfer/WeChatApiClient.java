package com.botsfer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Client for WeChat Official Account API (access token + customer service message).
 * See https://developers.weixin.qq.com/doc/offiaccount/en/Message_Management/Service_Center_messages.html
 */
@Component
public class WeChatApiClient {

    private static final Logger log = LoggerFactory.getLogger(WeChatApiClient.class);
    private static final String API_BASE = "https://api.weixin.qq.com/cgi-bin";

    private final RestTemplate restTemplate;
    private final WeChatConfig.WeChatProperties properties;

    private String cachedToken;
    private long tokenExpiresAt;

    public WeChatApiClient(RestTemplate restTemplate, WeChatConfig.WeChatProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    public boolean isConfigured() {
        return properties.isEnabled()
                && properties.getAppId() != null && !properties.getAppId().isBlank()
                && properties.getAppSecret() != null && !properties.getAppSecret().isBlank();
    }

    @SuppressWarnings("unchecked")
    public synchronized String getAccessToken() {
        if (cachedToken != null && System.currentTimeMillis() < tokenExpiresAt) {
            return cachedToken;
        }
        String url = API_BASE + "/token?grant_type=client_credential&appid="
                + properties.getAppId() + "&secret=" + properties.getAppSecret();
        ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
        Map<String, Object> body = response.getBody();
        if (body != null && body.containsKey("access_token")) {
            cachedToken = (String) body.get("access_token");
            Number expiresIn = (Number) body.get("expires_in");
            tokenExpiresAt = System.currentTimeMillis() + (expiresIn != null ? expiresIn.longValue() * 1000 - 60000 : 0);
        }
        return cachedToken;
    }

    public void sendTextMessage(String openId, String text) {
        String token = getAccessToken();
        if (token == null) return;
        String url = API_BASE + "/message/custom/send?access_token=" + token;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of(
                "touser", openId,
                "msgtype", "text",
                "text", Map.of("content", text != null ? text : "")
        );
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        restTemplate.exchange(url, HttpMethod.POST, request, Map.class);
    }
}
