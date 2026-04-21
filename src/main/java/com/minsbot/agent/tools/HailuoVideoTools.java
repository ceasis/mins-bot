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

/** MiniMax Hailuo video generation (T2V-01, I2V-01). */
@Component
public class HailuoVideoTools {

    private static final Logger log = LoggerFactory.getLogger(HailuoVideoTools.class);
    private static final String BASE = "https://api.minimax.chat/v1";

    @Value("${app.minimax.api-key:}")
    private String apiKey;

    @Value("${app.minimax.group-id:}")
    private String groupId;

    @Value("${app.minimax.model:MiniMax-Hailuo-02}")
    private String model;

    private final ToolExecutionNotifier notifier;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public HailuoVideoTools(ToolExecutionNotifier notifier) { this.notifier = notifier; }

    @Tool(description = "Generate a video with MiniMax Hailuo from a prompt. Strong motion quality. "
            + "Returns a task id; poll with getHailuoVideoStatus. Use when user says 'hailuo', 'minimax video'.")
    public String generateHailuoVideo(
            @ToolParam(description = "Text prompt describing the video") String prompt,
            @ToolParam(description = "Optional first-frame image URL for I2V") String firstFrameImage,
            @ToolParam(description = "Prompt optimizer? true/false") Boolean promptOptimizer) {
        if (!configured()) return notConfig();
        if (prompt == null || prompt.isBlank()) return "Provide a prompt.";
        notifier.notify("Submitting Hailuo video job...");
        try {
            java.util.Map<String, Object> body = new java.util.HashMap<>();
            body.put("model", model);
            body.put("prompt", prompt);
            if (firstFrameImage != null && !firstFrameImage.isBlank()) body.put("first_frame_image", firstFrameImage);
            if (promptOptimizer != null) body.put("prompt_optimizer", promptOptimizer);
            String json = mapper.writeValueAsString(body);
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(BASE + "/video_generation"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("[Hailuo] HTTP {}: {}", resp.statusCode(), truncate(resp.body(), 300));
                return "Hailuo rejected (HTTP " + resp.statusCode() + "): " + truncate(resp.body(), 250);
            }
            String id = mapper.readTree(resp.body()).path("task_id").asText("");
            return "🎬 Hailuo job submitted. task_id: " + id + "\nCheck with getHailuoVideoStatus('" + id + "') in ~90s.";
        } catch (Exception e) {
            log.warn("[Hailuo] submit failed: {}", e.getMessage());
            return "Failed to submit Hailuo video: " + e.getMessage();
        }
    }

    @Tool(description = "Check status of a Hailuo video task. Returns the file_id which can be fetched via MiniMax Files API.")
    public String getHailuoVideoStatus(@ToolParam(description = "Hailuo task_id") String taskId) {
        if (!configured()) return notConfig();
        if (taskId == null || taskId.isBlank()) return "Provide a task_id.";
        try {
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(BASE + "/query/video_generation?task_id=" + taskId))
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2)
                return "Status check failed (HTTP " + resp.statusCode() + "): " + truncate(resp.body(), 250);
            JsonNode root = mapper.readTree(resp.body());
            String status = root.path("status").asText("");
            if ("Success".equalsIgnoreCase(status)) {
                String fileId = root.path("file_id").asText("");
                return "✅ Hailuo video ready. file_id: " + fileId + "\nFetch via https://api.minimax.chat/v1/files/retrieve?GroupId="
                        + (groupId != null ? groupId : "<your-group-id>") + "&file_id=" + fileId;
            }
            if ("Fail".equalsIgnoreCase(status))
                return "❌ Hailuo job failed: " + root.path("base_resp").path("status_msg").asText("unknown");
            return "⏳ Hailuo status: " + status + ". Check again in ~30s.";
        } catch (Exception e) {
            return "Failed to check Hailuo status: " + e.getMessage();
        }
    }

    private boolean configured() { return apiKey != null && !apiKey.isBlank(); }
    private String notConfig() { return "MiniMax API key not set. Add it in Setup → Video generation (also add group ID)."; }
    private static String truncate(String s, int n) { return s == null ? "" : (s.length() > n ? s.substring(0, n) + "…" : s); }
}
