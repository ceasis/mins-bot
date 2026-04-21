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
import java.util.List;
import java.util.Map;

/** Synthesia — corporate-style AI avatar presenter videos. */
@Component
public class SynthesiaVideoTools {

    private static final Logger log = LoggerFactory.getLogger(SynthesiaVideoTools.class);
    private static final String BASE = "https://api.synthesia.io/v2";

    @Value("${app.synthesia.api-key:}")
    private String apiKey;

    @Value("${app.synthesia.default-avatar:anna_costume1_cameraA}")
    private String defaultAvatar;

    @Value("${app.synthesia.default-background:off_white}")
    private String defaultBackground;

    private final ToolExecutionNotifier notifier;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public SynthesiaVideoTools(ToolExecutionNotifier notifier) { this.notifier = notifier; }

    @Tool(description = "Generate a Synthesia AI presenter video from a script. Returns a video id; "
            + "poll with getSynthesiaVideo. Use when user says 'synthesia', 'corporate avatar video'.")
    public String generateSynthesiaVideo(
            @ToolParam(description = "Title for the video") String title,
            @ToolParam(description = "Script text the avatar should speak") String scriptText,
            @ToolParam(description = "Avatar id, optional (default anna_costume1_cameraA)") String avatarId,
            @ToolParam(description = "Background id, optional (default off_white)") String background,
            @ToolParam(description = "Is this a test render (free)? true/false, default true") Boolean test) {
        if (!configured()) return notConfig();
        if (scriptText == null || scriptText.isBlank()) return "Provide script text.";
        notifier.notify("Submitting Synthesia video...");
        try {
            Map<String, Object> scene = Map.of(
                    "scriptText", scriptText,
                    "avatar", (avatarId != null && !avatarId.isBlank()) ? avatarId : defaultAvatar,
                    "background", (background != null && !background.isBlank()) ? background : defaultBackground);
            Map<String, Object> body = Map.of(
                    "test", test == null ? Boolean.TRUE : test,
                    "title", title != null && !title.isBlank() ? title : "Mins Bot video",
                    "input", List.of(scene));
            String json = mapper.writeValueAsString(body);
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(BASE + "/videos"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", apiKey)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("[Synthesia] HTTP {}: {}", resp.statusCode(), truncate(resp.body(), 300));
                return "Synthesia rejected (HTTP " + resp.statusCode() + "): " + truncate(resp.body(), 250);
            }
            String id = mapper.readTree(resp.body()).path("id").asText("");
            return "🎬 Synthesia video submitted. id: " + id + "\nCheck with getSynthesiaVideo('" + id + "') in ~2-5min.";
        } catch (Exception e) {
            log.warn("[Synthesia] submit failed: {}", e.getMessage());
            return "Failed to submit Synthesia video: " + e.getMessage();
        }
    }

    @Tool(description = "Check status of a Synthesia video by id.")
    public String getSynthesiaVideo(@ToolParam(description = "Synthesia video id") String id) {
        if (!configured()) return notConfig();
        if (id == null || id.isBlank()) return "Provide an id.";
        try {
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(BASE + "/videos/" + id))
                    .header("Authorization", apiKey)
                    .timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2)
                return "Status check failed (HTTP " + resp.statusCode() + "): " + truncate(resp.body(), 250);
            JsonNode root = mapper.readTree(resp.body());
            String status = root.path("status").asText("");
            if ("complete".equalsIgnoreCase(status)) return "✅ Synthesia video ready: " + root.path("download").asText("");
            if ("rejected".equalsIgnoreCase(status) || "failed".equalsIgnoreCase(status))
                return "❌ Synthesia " + status + ": " + truncate(resp.body(), 300);
            return "⏳ Synthesia status: " + status + ". Check again in 30s+.";
        } catch (Exception e) {
            return "Failed to check Synthesia status: " + e.getMessage();
        }
    }

    private boolean configured() { return apiKey != null && !apiKey.isBlank(); }
    private String notConfig() { return "Synthesia API key not set. Add it in Setup → Video avatars."; }
    private static String truncate(String s, int n) { return s == null ? "" : (s.length() > n ? s.substring(0, n) + "…" : s); }
}
