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

/** Luma Labs Dream Machine / Ray 2 text-to-video. */
@Component
public class LumaVideoTools {

    private static final Logger log = LoggerFactory.getLogger(LumaVideoTools.class);
    private static final String BASE = "https://api.lumalabs.ai/dream-machine/v1";

    @Value("${app.luma.api-key:}")
    private String apiKey;

    @Value("${app.luma.model:ray-2}")
    private String model;

    private final ToolExecutionNotifier notifier;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public LumaVideoTools(ToolExecutionNotifier notifier) { this.notifier = notifier; }

    // @Tool removed — duplicate text-to-video provider. Canonical: SoraVideoTools.
    public String generateLumaVideo(
            @ToolParam(description = "Prompt describing the video") String prompt,
            @ToolParam(description = "Aspect ratio: '16:9','9:16','1:1','4:3','3:4','21:9','9:21'") String aspectRatio,
            @ToolParam(description = "Duration like '5s' or '9s'") String duration,
            @ToolParam(description = "Loop the video? true/false") Boolean loop) {
        if (!configured()) return notConfig();
        if (prompt == null || prompt.isBlank()) return "Provide a prompt.";
        Map<String, Object> body = new HashMap<>();
        body.put("prompt", prompt);
        body.put("model", model);
        body.put("aspect_ratio", (aspectRatio != null && !aspectRatio.isBlank()) ? aspectRatio : "16:9");
        body.put("duration", (duration != null && !duration.isBlank()) ? duration : "5s");
        if (loop != null) body.put("loop", loop);
        notifier.notify("Submitting Luma video job...");
        try {
            String json = mapper.writeValueAsString(body);
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(BASE + "/generations"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("[Luma] HTTP {}: {}", resp.statusCode(), truncate(resp.body(), 300));
                return "Luma rejected (HTTP " + resp.statusCode() + "): " + truncate(resp.body(), 250);
            }
            String id = mapper.readTree(resp.body()).path("id").asText("");
            return "🎬 Luma job submitted. id: " + id + "\nCheck with getLumaVideoStatus('" + id + "') in ~60s.";
        } catch (Exception e) {
            log.warn("[Luma] submit failed: {}", e.getMessage());
            return "Failed to submit Luma video: " + e.getMessage();
        }
    }

    // @Tool removed — companion to demoted generateLumaVideo.
    public String getLumaVideoStatus(@ToolParam(description = "Luma generation id") String id) {
        if (!configured()) return notConfig();
        if (id == null || id.isBlank()) return "Provide an id.";
        try {
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(BASE + "/generations/" + id))
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2)
                return "Status check failed (HTTP " + resp.statusCode() + "): " + truncate(resp.body(), 250);
            JsonNode root = mapper.readTree(resp.body());
            String state = root.path("state").asText("");
            if ("completed".equalsIgnoreCase(state)) {
                String url = root.path("assets").path("video").asText("");
                return "✅ Luma video ready: " + url;
            }
            if ("failed".equalsIgnoreCase(state))
                return "❌ Luma job failed: " + root.path("failure_reason").asText("unknown");
            return "⏳ Luma state: " + state + ". Check again in ~20s.";
        } catch (Exception e) {
            return "Failed to check Luma status: " + e.getMessage();
        }
    }

    private boolean configured() { return apiKey != null && !apiKey.isBlank(); }
    private String notConfig() { return "Luma API key not set. Add it in Setup → Video generation."; }
    private static String truncate(String s, int n) { return s == null ? "" : (s.length() > n ? s.substring(0, n) + "…" : s); }
}
