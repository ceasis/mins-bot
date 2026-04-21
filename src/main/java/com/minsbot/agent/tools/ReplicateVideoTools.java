package com.minsbot.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
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
 * Replicate — aggregator for Kling, Hailuo, Luma, Hunyuan, Wan 2.1, LTX-Video, Mochi,
 * Stable Video Diffusion, etc. One API, many models.
 */
@Component
public class ReplicateVideoTools {

    private static final Logger log = LoggerFactory.getLogger(ReplicateVideoTools.class);
    private static final String BASE = "https://api.replicate.com/v1";

    @Value("${app.replicate.api-key:}")
    private String apiKey;

    private final ToolExecutionNotifier notifier;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public ReplicateVideoTools(ToolExecutionNotifier notifier) { this.notifier = notifier; }

    @Tool(description = "Generate a video via Replicate. Use any supported model slug "
            + "(e.g. 'kwaivgi/kling-v2.1-master', 'minimax/video-01', 'lightricks/ltx-video', "
            + "'tencent/hunyuan-video', 'wan-video/wan-2.1-t2v-14b'). "
            + "Returns a prediction id; poll with getReplicatePrediction.")
    public String generateReplicateVideo(
            @ToolParam(description = "Model slug in 'owner/name' form, optionally with :version hash") String modelSlug,
            @ToolParam(description = "Prompt describing the video") String prompt,
            @ToolParam(description = "Optional starting image URL (for image-to-video models)") String imageUrl,
            @ToolParam(description = "Optional aspect ratio: '16:9','9:16','1:1'") String aspectRatio) {
        if (!configured()) return notConfig();
        if (modelSlug == null || modelSlug.isBlank()) return "Provide a model slug.";
        if (prompt == null || prompt.isBlank()) return "Provide a prompt.";
        notifier.notify("Submitting Replicate prediction...");
        try {
            Map<String, Object> input = new HashMap<>();
            input.put("prompt", prompt);
            if (imageUrl != null && !imageUrl.isBlank()) input.put("image", imageUrl);
            if (aspectRatio != null && !aspectRatio.isBlank()) input.put("aspect_ratio", aspectRatio);

            String url;
            Map<String, Object> body = new HashMap<>();
            body.put("input", input);
            if (modelSlug.contains(":")) {
                String[] parts = modelSlug.split(":", 2);
                body.put("version", parts[1]);
                url = BASE + "/predictions";
            } else {
                url = BASE + "/models/" + modelSlug + "/predictions";
            }
            String json = mapper.writeValueAsString(body);
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Prefer", "wait=1")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("[Replicate] HTTP {}: {}", resp.statusCode(), truncate(resp.body(), 300));
                return "Replicate rejected (HTTP " + resp.statusCode() + "): " + truncate(resp.body(), 250);
            }
            JsonNode root = mapper.readTree(resp.body());
            String id = root.path("id").asText("");
            String status = root.path("status").asText("");
            return "🎬 Replicate prediction submitted. id: " + id + " (status: " + status
                    + ")\nCheck with getReplicatePrediction('" + id + "') in ~20-60s.";
        } catch (Exception e) {
            log.warn("[Replicate] submit failed: {}", e.getMessage());
            return "Failed to submit Replicate prediction: " + e.getMessage();
        }
    }

    @Tool(description = "Check the status/output of a Replicate prediction by id.")
    public String getReplicatePrediction(@ToolParam(description = "Replicate prediction id") String id) {
        if (!configured()) return notConfig();
        if (id == null || id.isBlank()) return "Provide an id.";
        try {
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(BASE + "/predictions/" + id))
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2)
                return "Status check failed (HTTP " + resp.statusCode() + "): " + truncate(resp.body(), 250);
            JsonNode root = mapper.readTree(resp.body());
            String status = root.path("status").asText("");
            if ("succeeded".equalsIgnoreCase(status)) {
                JsonNode out = root.path("output");
                String url = out.isArray() ? out.path(0).asText("") : out.asText("");
                return "✅ Replicate output: " + url;
            }
            if ("failed".equalsIgnoreCase(status) || "canceled".equalsIgnoreCase(status))
                return "❌ Replicate " + status + ": " + root.path("error").asText("unknown");
            return "⏳ Replicate status: " + status + ". Check again in ~20s.";
        } catch (Exception e) {
            return "Failed to check Replicate status: " + e.getMessage();
        }
    }

    private boolean configured() { return apiKey != null && !apiKey.isBlank(); }
    private String notConfig() { return "Replicate API token not set. Add it in Setup → Video aggregators."; }
    private static String truncate(String s, int n) { return s == null ? "" : (s.length() > n ? s.substring(0, n) + "…" : s); }
}
