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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Google Veo (text-to-video) via the Gemini API using the existing
 * {@code gemini.api.key}. Uses the long-running-operation pattern:
 * submit → poll operation → fetch video URI. No Vertex AI / GCP project required —
 * just the same Gemini API key that powers vision and reasoning.
 *
 * <p>Pricing note: Veo is a paid feature on the Gemini API. The user's
 * Gemini billing / credits apply.
 */
@Component
public class VeoVideoTools {

    private static final Logger log = LoggerFactory.getLogger(VeoVideoTools.class);
    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta";

    @Value("${gemini.api.key:}")
    private String apiKey;

    @Value("${app.veo.model:veo-3.0-generate-preview}")
    private String model;

    private final ToolExecutionNotifier notifier;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    public VeoVideoTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    // ─── Generation ─────────────────────────────────────────────────

    @Tool(description = "Generate an AI video with Google Veo from a text prompt. Uses your Gemini API key "
            + "and Gemini credits. USE THIS when the user says 'generate a video of X', 'make a veo video', "
            + "'create an AI video of a cat riding a skateboard', 'text to video'. "
            + "Returns an operation name — poll with getVeoVideoStatus to get the final MP4 URL. "
            + "Generation usually takes 60-180 seconds. No avatar, no voice — this is cinematic AI video.")
    public String generateVeoVideo(
            @ToolParam(description = "Prompt describing the video. Be cinematic and specific (subject, action, setting, camera movement, lighting, style).") String prompt,
            @ToolParam(description = "Aspect ratio: '16:9' (landscape, default), '9:16' (portrait), or '1:1' (square)") String aspectRatio,
            @ToolParam(description = "Duration in seconds (Veo default is 8; valid range depends on the model)") Integer durationSeconds,
            @ToolParam(description = "Number of video samples to generate (1-4, default 1)") Integer sampleCount) {
        if (!isConfigured()) return notConfigured();
        if (prompt == null || prompt.isBlank()) return "Please provide a prompt describing the video.";

        String ar = (aspectRatio != null && !aspectRatio.isBlank()) ? aspectRatio.trim() : "16:9";
        int dur = durationSeconds != null && durationSeconds > 0 ? durationSeconds : 8;
        int n = sampleCount != null && sampleCount > 0 ? Math.min(sampleCount, 4) : 1;

        notifier.notify("Submitting Veo video job...");
        try {
            Map<String, Object> body = Map.of(
                    "instances", List.of(Map.of("prompt", prompt)),
                    "parameters", Map.of(
                            "aspectRatio", ar,
                            "durationSeconds", String.valueOf(dur),
                            "sampleCount", n,
                            "enhancePrompt", true));
            String bodyJson = mapper.writeValueAsString(body);

            String url = BASE + "/models/" + model + ":predictLongRunning";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", apiKey)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("[Veo] submit HTTP {}: {}", resp.statusCode(), truncate(resp.body(), 300));
                return "Veo rejected the job (HTTP " + resp.statusCode() + "): " + truncate(resp.body(), 250);
            }
            JsonNode root = mapper.readTree(resp.body());
            String opName = root.path("name").asText("");
            if (opName.isEmpty()) {
                return "Veo response missing operation name: " + truncate(resp.body(), 200);
            }
            return "🎬 Veo video job submitted. operation: " + opName
                    + "\nCheck with getVeoVideoStatus('" + opName + "') in ~60 seconds.";
        } catch (Exception e) {
            log.warn("[Veo] submit failed: {}", e.getMessage());
            return "Failed to submit Veo video: " + e.getMessage();
        }
    }

    @Tool(description = "Check the status of a Veo video generation job. Returns 'processing', "
            + "'completed' with the MP4 URL, or 'failed' with reason. Use after generateVeoVideo.")
    public String getVeoVideoStatus(
            @ToolParam(description = "Operation name returned by generateVeoVideo (looks like 'models/veo-3.0.../operations/...')") String operationName) {
        if (!isConfigured()) return notConfigured();
        if (operationName == null || operationName.isBlank()) return "Provide an operation name.";
        try {
            // operationName already contains the full path; escape slashes in URL construction
            String opPath = operationName.startsWith("/") ? operationName.substring(1) : operationName;
            String url = BASE + "/" + opPath;
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("x-goog-api-key", apiKey)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("[Veo] status HTTP {}: {}", resp.statusCode(), truncate(resp.body(), 300));
                return "Veo status check failed (HTTP " + resp.statusCode() + "): "
                        + truncate(resp.body(), 250);
            }
            JsonNode root = mapper.readTree(resp.body());
            boolean done = root.path("done").asBoolean(false);
            if (!done) {
                return "⏳ Still processing. Check again in 20-30 seconds.";
            }
            // Done — look for error first
            JsonNode err = root.path("error");
            if (!err.isMissingNode() && !err.isNull() && err.has("message")) {
                return "❌ Veo job failed: " + err.path("message").asText("");
            }
            // Look for generated video URIs
            JsonNode samples = root.path("response").path("generateVideoResponse").path("generatedSamples");
            if (!samples.isArray() || samples.isEmpty()) {
                // Fall back to broader response dump for debugging
                return "Veo reports done but no samples found:\n" + truncate(root.toString(), 600);
            }
            StringBuilder sb = new StringBuilder("✅ Veo video ready!\n\n");
            int i = 0;
            for (JsonNode s : samples) {
                i++;
                String uri = s.path("video").path("uri").asText("");
                if (uri.isEmpty()) uri = s.path("uri").asText("");
                sb.append("Sample ").append(i).append(": ").append(uri).append("\n");
            }
            sb.append("\nNote: Veo video URIs may require your Gemini API key to download.");
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("[Veo] status failed: {}", e.getMessage());
            return "Failed to check Veo status: " + e.getMessage();
        }
    }

    @Tool(description = "Download a Veo-generated video to a local file. The video URI from Veo is "
            + "authenticated — this tool adds the API key and saves the MP4 to disk.")
    public String downloadVeoVideo(
            @ToolParam(description = "Video URI returned by getVeoVideoStatus") String videoUri,
            @ToolParam(description = "Absolute local path to save the MP4 file") String savePath) {
        if (!isConfigured()) return notConfigured();
        if (videoUri == null || videoUri.isBlank()) return "Provide a video URI.";
        if (savePath == null || savePath.isBlank()) return "Provide a save path.";
        notifier.notify("Downloading Veo video...");
        try {
            String url = videoUri.contains("?")
                    ? videoUri + "&key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
                    : videoUri + "?key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMinutes(2))
                    .GET().build();
            java.nio.file.Path out = java.nio.file.Paths.get(savePath);
            java.nio.file.Files.createDirectories(out.getParent() != null ? out.getParent() : out);
            HttpResponse<java.nio.file.Path> resp = http.send(req,
                    HttpResponse.BodyHandlers.ofFile(out));
            if (resp.statusCode() / 100 != 2) {
                java.nio.file.Files.deleteIfExists(out);
                return "Download failed: HTTP " + resp.statusCode();
            }
            long size = java.nio.file.Files.size(out);
            return "✅ Saved " + size + " bytes to " + out;
        } catch (Exception e) {
            return "Download failed: " + e.getMessage();
        }
    }

    // ─── Internals ──────────────────────────────────────────────────

    private boolean isConfigured() { return apiKey != null && !apiKey.isBlank(); }

    private String notConfigured() {
        return "Gemini API key not set. Add it in Setup → Chat & LLM → 'Google Gemini API key' "
                + "(property: gemini.api.key). Veo uses the same key.";
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }
}
