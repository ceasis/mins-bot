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
import java.util.Map;

/** Runway Gen-4 image/text-to-video via the dev API. */
@Component
public class RunwayVideoTools {

    private static final Logger log = LoggerFactory.getLogger(RunwayVideoTools.class);
    private static final String BASE = "https://api.dev.runwayml.com/v1";
    private static final String API_VERSION = "2024-11-06";

    @Value("${app.runway.api-key:}")
    private String apiKey;

    @Value("${app.runway.model:gen4_turbo}")
    private String model;

    private final ToolExecutionNotifier notifier;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public RunwayVideoTools(ToolExecutionNotifier notifier) { this.notifier = notifier; }

    // @Tool removed — duplicate text-to-video provider. Canonical: SoraVideoTools.
    public String generateRunwayVideo(
            @ToolParam(description = "Text prompt describing motion/scene") String prompt,
            @ToolParam(description = "Public image URL to animate (Runway Gen-4 requires an image input)") String imageUrl,
            @ToolParam(description = "Duration in seconds: 5 or 10") Integer duration,
            @ToolParam(description = "Ratio: '1280:720', '720:1280', '1104:832' etc.") String ratio) {
        if (!configured()) return notConfig();
        if (prompt == null || prompt.isBlank()) return "Provide a prompt.";
        if (imageUrl == null || imageUrl.isBlank()) return "Runway Gen-4 needs a starting image URL.";
        int dur = duration != null && duration > 0 ? duration : 5;
        String r = (ratio != null && !ratio.isBlank()) ? ratio : "1280:720";
        notifier.notify("Submitting Runway video job...");
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "model", model, "promptText", prompt, "promptImage", imageUrl,
                    "ratio", r, "duration", dur));
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(BASE + "/image_to_video"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("X-Runway-Version", API_VERSION)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("[Runway] HTTP {}: {}", resp.statusCode(), truncate(resp.body(), 300));
                return "Runway rejected (HTTP " + resp.statusCode() + "): " + truncate(resp.body(), 250);
            }
            String id = mapper.readTree(resp.body()).path("id").asText("");
            return "🎬 Runway job submitted. id: " + id + "\nCheck with getRunwayVideoStatus('" + id + "') in ~60s.";
        } catch (Exception e) {
            log.warn("[Runway] submit failed: {}", e.getMessage());
            return "Failed to submit Runway video: " + e.getMessage();
        }
    }

    // @Tool removed — companion to demoted generateRunwayVideo.
    public String getRunwayVideoStatus(@ToolParam(description = "Runway task id") String id) {
        if (!configured()) return notConfig();
        if (id == null || id.isBlank()) return "Provide an id.";
        try {
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(BASE + "/tasks/" + id))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("X-Runway-Version", API_VERSION)
                    .timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2)
                return "Status check failed (HTTP " + resp.statusCode() + "): " + truncate(resp.body(), 250);
            JsonNode root = mapper.readTree(resp.body());
            String status = root.path("status").asText("");
            if ("SUCCEEDED".equalsIgnoreCase(status)) {
                StringBuilder sb = new StringBuilder("✅ Runway video ready:\n");
                for (JsonNode u : root.path("output")) sb.append(u.asText()).append('\n');
                return sb.toString().trim();
            }
            if ("FAILED".equalsIgnoreCase(status) || "CANCELED".equalsIgnoreCase(status))
                return "❌ Runway job " + status + ": " + root.path("failure").asText("");
            return "⏳ Runway status: " + status + ". Check again in ~20s.";
        } catch (Exception e) {
            return "Failed to check Runway status: " + e.getMessage();
        }
    }

    private boolean configured() { return apiKey != null && !apiKey.isBlank(); }
    private String notConfig() { return "Runway API key not set. Add it in Setup → Video generation."; }
    private static String truncate(String s, int n) { return s == null ? "" : (s.length() > n ? s.substring(0, n) + "…" : s); }
}
