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

/** Tavus — personalized replica videos of you (or a stock replica) speaking a script. */
@Component
public class TavusVideoTools {

    private static final Logger log = LoggerFactory.getLogger(TavusVideoTools.class);
    private static final String BASE = "https://tavusapi.com/v2";

    @Value("${app.tavus.api-key:}")
    private String apiKey;

    @Value("${app.tavus.default-replica-id:}")
    private String defaultReplicaId;

    private final ToolExecutionNotifier notifier;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public TavusVideoTools(ToolExecutionNotifier notifier) { this.notifier = notifier; }

    @Tool(description = "Generate a personalized avatar video with Tavus from a replica id + script. "
            + "Returns a video id; poll with getTavusVideo. Use when user says 'tavus', 'personalized replica video'.")
    public String generateTavusVideo(
            @ToolParam(description = "Script text for the replica to speak") String scriptText,
            @ToolParam(description = "Replica id (optional if default is set)") String replicaId,
            @ToolParam(description = "Optional video title") String videoName) {
        if (!configured()) return notConfig();
        if (scriptText == null || scriptText.isBlank()) return "Provide script text.";
        String replica = (replicaId != null && !replicaId.isBlank()) ? replicaId : defaultReplicaId;
        if (replica == null || replica.isBlank())
            return "No replica id. Provide one or set app.tavus.default-replica-id.";
        notifier.notify("Submitting Tavus video...");
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("replica_id", replica);
            body.put("script", scriptText);
            if (videoName != null && !videoName.isBlank()) body.put("video_name", videoName);
            String json = mapper.writeValueAsString(body);
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(BASE + "/videos"))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("[Tavus] HTTP {}: {}", resp.statusCode(), truncate(resp.body(), 300));
                return "Tavus rejected (HTTP " + resp.statusCode() + "): " + truncate(resp.body(), 250);
            }
            JsonNode root = mapper.readTree(resp.body());
            String id = root.path("video_id").asText("");
            return "🎬 Tavus video submitted. video_id: " + id + "\nCheck with getTavusVideo('" + id + "') in ~1-3min.";
        } catch (Exception e) {
            log.warn("[Tavus] submit failed: {}", e.getMessage());
            return "Failed to submit Tavus video: " + e.getMessage();
        }
    }

    @Tool(description = "Check status of a Tavus video by id.")
    public String getTavusVideo(@ToolParam(description = "Tavus video_id") String id) {
        if (!configured()) return notConfig();
        if (id == null || id.isBlank()) return "Provide a video_id.";
        try {
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(BASE + "/videos/" + id))
                    .header("x-api-key", apiKey)
                    .timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2)
                return "Status check failed (HTTP " + resp.statusCode() + "): " + truncate(resp.body(), 250);
            JsonNode root = mapper.readTree(resp.body());
            String status = root.path("status").asText("");
            if ("ready".equalsIgnoreCase(status) || "completed".equalsIgnoreCase(status))
                return "✅ Tavus video ready: " + root.path("download_url").asText(root.path("hosted_url").asText(""));
            if ("error".equalsIgnoreCase(status) || "failed".equalsIgnoreCase(status))
                return "❌ Tavus failed: " + truncate(resp.body(), 300);
            return "⏳ Tavus status: " + status + ". Check again in ~30s.";
        } catch (Exception e) {
            return "Failed to check Tavus status: " + e.getMessage();
        }
    }

    private boolean configured() { return apiKey != null && !apiKey.isBlank(); }
    private String notConfig() { return "Tavus API key not set. Add it in Setup → Video avatars."; }
    private static String truncate(String s, int n) { return s == null ? "" : (s.length() > n ? s.substring(0, n) + "…" : s); }
}
