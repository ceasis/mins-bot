package com.minsbot.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Kling AI (Kuaishou) text-to-video. Uses HMAC-SHA256 JWT auth built from
 * the access_key + secret_key pair.
 */
@Component
public class KlingVideoTools {

    private static final Logger log = LoggerFactory.getLogger(KlingVideoTools.class);
    private static final String BASE = "https://api.klingai.com";

    @Value("${app.kling.access-key:}")
    private String accessKey;

    @Value("${app.kling.secret-key:}")
    private String secretKey;

    @Value("${app.kling.model:kling-v2-master}")
    private String model;

    private final ToolExecutionNotifier notifier;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public KlingVideoTools(ToolExecutionNotifier notifier) { this.notifier = notifier; }

    // @Tool removed — duplicate text-to-video provider. Canonical: SoraVideoTools.
    public String generateKlingVideo(
            @ToolParam(description = "Prompt describing the video") String prompt,
            @ToolParam(description = "Aspect ratio: '16:9','9:16','1:1'") String aspectRatio,
            @ToolParam(description = "Duration seconds: 5 or 10") Integer duration) {
        if (!configured()) return notConfig();
        if (prompt == null || prompt.isBlank()) return "Provide a prompt.";
        notifier.notify("Submitting Kling video job...");
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model_name", model);
            body.put("prompt", prompt);
            body.put("aspect_ratio", (aspectRatio != null && !aspectRatio.isBlank()) ? aspectRatio : "16:9");
            body.put("duration", String.valueOf(duration != null && duration > 0 ? duration : 5));
            String json = mapper.writeValueAsString(body);
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(BASE + "/v1/videos/text2video"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + buildJwt())
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("[Kling] HTTP {}: {}", resp.statusCode(), truncate(resp.body(), 300));
                return "Kling rejected (HTTP " + resp.statusCode() + "): " + truncate(resp.body(), 250);
            }
            JsonNode root = mapper.readTree(resp.body());
            String id = root.path("data").path("task_id").asText("");
            return "🎬 Kling job submitted. task_id: " + id + "\nCheck with getKlingVideoStatus('" + id + "') in ~90s.";
        } catch (Exception e) {
            log.warn("[Kling] submit failed: {}", e.getMessage());
            return "Failed to submit Kling video: " + e.getMessage();
        }
    }

    // @Tool removed — companion to demoted generateKlingVideo.
    public String getKlingVideoStatus(@ToolParam(description = "Kling task_id") String taskId) {
        if (!configured()) return notConfig();
        if (taskId == null || taskId.isBlank()) return "Provide a task_id.";
        try {
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(BASE + "/v1/videos/text2video/" + taskId))
                    .header("Authorization", "Bearer " + buildJwt())
                    .timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2)
                return "Status check failed (HTTP " + resp.statusCode() + "): " + truncate(resp.body(), 250);
            JsonNode data = mapper.readTree(resp.body()).path("data");
            String status = data.path("task_status").asText("");
            if ("succeed".equalsIgnoreCase(status)) {
                String url = data.path("task_result").path("videos").path(0).path("url").asText("");
                return "✅ Kling video ready: " + url;
            }
            if ("failed".equalsIgnoreCase(status))
                return "❌ Kling job failed: " + data.path("task_status_msg").asText("unknown");
            return "⏳ Kling status: " + status + ". Check again in ~30s.";
        } catch (Exception e) {
            return "Failed to check Kling status: " + e.getMessage();
        }
    }

    private String buildJwt() throws Exception {
        long now = Instant.now().getEpochSecond();
        String header = b64Url(mapper.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT")));
        String payload = b64Url(mapper.writeValueAsBytes(Map.of(
                "iss", accessKey,
                "exp", now + 1800,
                "nbf", now - 5)));
        String signingInput = header + "." + payload;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = b64Url(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
        return signingInput + "." + sig;
    }

    private static String b64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private boolean configured() {
        return accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank();
    }
    private String notConfig() { return "Kling access-key/secret-key not set. Add in Setup → Video generation."; }
    private static String truncate(String s, int n) { return s == null ? "" : (s.length() > n ? s.substring(0, n) + "…" : s); }
}
