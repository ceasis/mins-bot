package com.minsbot.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * fal.ai — aggregator hosting Veo, Kling, Luma, MiniMax, LTX, Wan 2.1, and more.
 * Uses the queue API: POST to queue.fal.run/{model} then poll status.
 */
@Component
public class FalVideoTools {

    private static final Logger log = LoggerFactory.getLogger(FalVideoTools.class);
    private static final String QUEUE = "https://queue.fal.run";

    @Value("${app.falai.api-key:}")
    private String apiKey;

    private final ToolExecutionNotifier notifier;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public FalVideoTools(ToolExecutionNotifier notifier) { this.notifier = notifier; }

    // @Tool removed - duplicate provider; canonical SoraVideoTools.
    public String generateFalVideo(
            @ToolParam(description = "fal.ai model slug, e.g. 'fal-ai/kling-video/v2/master/text-to-video'") String modelSlug,
            @ToolParam(description = "Prompt describing the video") String prompt,
            @ToolParam(description = "Optional image URL for image-to-video models") String imageUrl,
            @ToolParam(description = "Optional aspect ratio: '16:9','9:16','1:1'") String aspectRatio) {
        if (!configured()) return notConfig();
        if (modelSlug == null || modelSlug.isBlank()) return "Provide a model slug.";
        if (prompt == null || prompt.isBlank()) return "Provide a prompt.";
        notifier.notify("Submitting fal.ai request...");
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("prompt", prompt);
            if (imageUrl != null && !imageUrl.isBlank()) body.put("image_url", imageUrl);
            if (aspectRatio != null && !aspectRatio.isBlank()) body.put("aspect_ratio", aspectRatio);
            String json = mapper.writeValueAsString(body);
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(QUEUE + "/" + modelSlug))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Key " + apiKey)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("[fal] HTTP {}: {}", resp.statusCode(), truncate(resp.body(), 300));
                return "fal.ai rejected (HTTP " + resp.statusCode() + "): " + truncate(resp.body(), 250);
            }
            JsonNode root = mapper.readTree(resp.body());
            String reqId = root.path("request_id").asText("");
            String statusUrl = root.path("status_url").asText("");
            return "🎬 fal.ai request submitted. request_id: " + reqId
                    + "\nModel: " + modelSlug
                    + "\nCheck with getFalRequest('" + modelSlug + "', '" + reqId + "') in ~30s."
                    + (statusUrl.isEmpty() ? "" : "\nOr poll directly: " + statusUrl);
        } catch (Exception e) {
            log.warn("[fal] submit failed: {}", e.getMessage());
            return "Failed to submit fal.ai request: " + e.getMessage();
        }
    }

    // @Tool removed - duplicate provider; canonical SoraVideoTools.
    public String getFalRequest(
            @ToolParam(description = "fal.ai model slug used for generation") String modelSlug,
            @ToolParam(description = "Request id returned by generate") String requestId) {
        if (!configured()) return notConfig();
        if (requestId == null || requestId.isBlank()) return "Provide a request_id.";
        try {
            String statusUrl = QUEUE + "/" + modelSlug + "/requests/" + requestId + "/status";
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(statusUrl))
                    .header("Authorization", "Key " + apiKey)
                    .timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2)
                return "Status check failed (HTTP " + resp.statusCode() + "): " + truncate(resp.body(), 250);
            String status = mapper.readTree(resp.body()).path("status").asText("");
            if (!"COMPLETED".equalsIgnoreCase(status))
                return "⏳ fal.ai status: " + status + ". Check again in ~20s.";
            // fetch result
            String resultUrl = QUEUE + "/" + modelSlug + "/requests/" + requestId;
            HttpResponse<String> r2 = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(resultUrl))
                    .header("Authorization", "Key " + apiKey)
                    .timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(r2.body());
            String video = root.path("video").path("url").asText("");
            if (video.isEmpty()) video = root.path("video_url").asText("");
            return "✅ fal.ai output: " + (video.isEmpty() ? truncate(r2.body(), 400) : video);
        } catch (Exception e) {
            return "Failed to check fal.ai status: " + e.getMessage();
        }
    }

    private boolean configured() { return apiKey != null && !apiKey.isBlank(); }
    private String notConfig() { return "fal.ai API key not set. Add it in Setup → Video aggregators."; }
    private static String truncate(String s, int n) { return s == null ? "" : (s.length() > n ? s.substring(0, n) + "…" : s); }
}
