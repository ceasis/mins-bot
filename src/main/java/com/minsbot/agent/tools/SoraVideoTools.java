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
import java.util.Map;

/** OpenAI Sora 2 text-to-video. Uses the /videos endpoint. */
@Component
public class SoraVideoTools {

    private static final Logger log = LoggerFactory.getLogger(SoraVideoTools.class);
    private static final String BASE = "https://api.openai.com/v1";

    @Value("${app.sora.api-key:${spring.ai.openai.api-key:}}")
    private String apiKey;

    @Value("${app.sora.model:sora-2}")
    private String model;

    private final ToolExecutionNotifier notifier;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public SoraVideoTools(ToolExecutionNotifier notifier) { this.notifier = notifier; }

    @com.minsbot.offline.RequiresOnline("OpenAI Sora video generation")
    @Tool(description = "Generate an AI video with OpenAI Sora 2 from a text prompt. Returns a job ID; "
            + "poll with getSoraVideoStatus. Use when user says 'sora video', 'openai video', 'make a sora clip'.")
    public String generateSoraVideo(
            @ToolParam(description = "Text prompt describing the video") String prompt,
            @ToolParam(description = "Resolution, e.g. '1280x720' or '1920x1080'") String size,
            @ToolParam(description = "Duration in seconds (Sora 2 supports up to 20)") Integer seconds) {
        if (!configured()) return notConfig();
        if (prompt == null || prompt.isBlank()) return "Provide a prompt.";
        String res = (size != null && !size.isBlank()) ? size : "1280x720";
        int dur = seconds != null && seconds > 0 ? seconds : 8;
        notifier.notify("Submitting Sora video job...");
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "model", model, "prompt", prompt,
                    "size", res, "seconds", String.valueOf(dur)));
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(BASE + "/videos"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("[Sora] HTTP {}: {}", resp.statusCode(), truncate(resp.body(), 300));
                return "Sora rejected the job (HTTP " + resp.statusCode() + "): " + truncate(resp.body(), 250);
            }
            String id = mapper.readTree(resp.body()).path("id").asText("");
            return "🎬 Sora job submitted. id: " + id + "\nCheck with getSoraVideoStatus('" + id + "') in ~60s.";
        } catch (Exception e) {
            log.warn("[Sora] submit failed: {}", e.getMessage());
            return "Failed to submit Sora video: " + e.getMessage();
        }
    }

    @Tool(description = "Check status of a Sora video job by id.")
    public String getSoraVideoStatus(@ToolParam(description = "Sora job id") String id) {
        if (!configured()) return notConfig();
        if (id == null || id.isBlank()) return "Provide an id.";
        try {
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(BASE + "/videos/" + id))
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2)
                return "Status check failed (HTTP " + resp.statusCode() + "): " + truncate(resp.body(), 250);
            JsonNode root = mapper.readTree(resp.body());
            String status = root.path("status").asText("");
            if ("completed".equalsIgnoreCase(status) || "succeeded".equalsIgnoreCase(status)) {
                String url = root.path("output").path("url").asText("");
                if (url.isEmpty()) url = root.path("url").asText("");
                return "✅ Sora video ready: " + url;
            }
            if ("failed".equalsIgnoreCase(status) || "error".equalsIgnoreCase(status))
                return "❌ Sora job failed: " + root.path("error").path("message").asText("unknown");
            return "⏳ Sora status: " + status + ". Check again in ~20s.";
        } catch (Exception e) {
            return "Failed to check Sora status: " + e.getMessage();
        }
    }

    private boolean configured() { return apiKey != null && !apiKey.isBlank(); }
    private String notConfig() { return "OpenAI / Sora API key not set. Add it in Setup → Video generation."; }
    private static String truncate(String s, int n) { return s == null ? "" : (s.length() > n ? s.substring(0, n) + "…" : s); }
}
