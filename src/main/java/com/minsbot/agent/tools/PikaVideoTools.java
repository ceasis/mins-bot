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

/** Pika Labs text-to-video (via their partner REST API). */
@Component
public class PikaVideoTools {

    private static final Logger log = LoggerFactory.getLogger(PikaVideoTools.class);
    private static final String BASE = "https://api.pika.art";

    @Value("${app.pika.api-key:}")
    private String apiKey;

    @Value("${app.pika.model:2.1}")
    private String model;

    private final ToolExecutionNotifier notifier;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public PikaVideoTools(ToolExecutionNotifier notifier) { this.notifier = notifier; }

    @Tool(description = "Generate a short stylized video with Pika Labs from a prompt. Returns a job id; "
            + "poll with getPikaVideoStatus. Use when user says 'pika video', 'pika labs', 'stylized short video'.")
    public String generatePikaVideo(
            @ToolParam(description = "Prompt describing the video") String prompt,
            @ToolParam(description = "Aspect ratio: '16:9','9:16','1:1'") String aspectRatio,
            @ToolParam(description = "Negative prompt — what to avoid (optional)") String negativePrompt) {
        if (!configured()) return notConfig();
        if (prompt == null || prompt.isBlank()) return "Provide a prompt.";
        Map<String, Object> body = new HashMap<>();
        body.put("promptText", prompt);
        body.put("aspectRatio", (aspectRatio != null && !aspectRatio.isBlank()) ? aspectRatio : "16:9");
        if (negativePrompt != null && !negativePrompt.isBlank()) body.put("negativePrompt", negativePrompt);
        notifier.notify("Submitting Pika video job...");
        try {
            String json = mapper.writeValueAsString(body);
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(BASE + "/generate/" + model))
                    .header("Content-Type", "application/json")
                    .header("X-API-KEY", apiKey)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("[Pika] HTTP {}: {}", resp.statusCode(), truncate(resp.body(), 300));
                return "Pika rejected (HTTP " + resp.statusCode() + "): " + truncate(resp.body(), 250);
            }
            JsonNode root = mapper.readTree(resp.body());
            String id = root.path("video_id").asText(root.path("id").asText(""));
            return "🎬 Pika job submitted. id: " + id + "\nCheck with getPikaVideoStatus('" + id + "') in ~60s.";
        } catch (Exception e) {
            log.warn("[Pika] submit failed: {}", e.getMessage());
            return "Failed to submit Pika video: " + e.getMessage();
        }
    }

    @Tool(description = "Check status of a Pika video job by id.")
    public String getPikaVideoStatus(@ToolParam(description = "Pika video id") String id) {
        if (!configured()) return notConfig();
        if (id == null || id.isBlank()) return "Provide an id.";
        try {
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(BASE + "/videos/" + id))
                    .header("X-API-KEY", apiKey)
                    .timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2)
                return "Status check failed (HTTP " + resp.statusCode() + "): " + truncate(resp.body(), 250);
            JsonNode root = mapper.readTree(resp.body());
            String status = root.path("status").asText("");
            if ("finished".equalsIgnoreCase(status) || "completed".equalsIgnoreCase(status)) {
                String url = root.path("url").asText(root.path("videoUrl").asText(""));
                return "✅ Pika video ready: " + url;
            }
            if ("failed".equalsIgnoreCase(status) || "error".equalsIgnoreCase(status))
                return "❌ Pika job failed: " + root.path("error").asText("unknown");
            return "⏳ Pika status: " + status + ". Check again in ~20s.";
        } catch (Exception e) {
            return "Failed to check Pika status: " + e.getMessage();
        }
    }

    private boolean configured() { return apiKey != null && !apiKey.isBlank(); }
    private String notConfig() { return "Pika API key not set. Add it in Setup → Video generation. Note: Pika's public API is partner-gated."; }
    private static String truncate(String s, int n) { return s == null ? "" : (s.length() > n ? s.substring(0, n) + "…" : s); }
}
