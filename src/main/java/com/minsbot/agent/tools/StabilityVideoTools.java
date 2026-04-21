package com.minsbot.agent.tools;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Stability AI — Stable Video Diffusion (image-to-video).
 * Requires a local starting image (PNG/JPG 1024x576, 576x1024 or 768x768).
 */
@Component
public class StabilityVideoTools {

    private static final Logger log = LoggerFactory.getLogger(StabilityVideoTools.class);
    private static final String BASE = "https://api.stability.ai/v2beta";

    @Value("${app.stability.api-key:}")
    private String apiKey;

    private final ToolExecutionNotifier notifier;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public StabilityVideoTools(ToolExecutionNotifier notifier) { this.notifier = notifier; }

    @Tool(description = "Generate a short video with Stable Video Diffusion from a local image file. "
            + "Returns a generation id; poll with getStabilityVideoStatus. Cheap open-weights option.")
    public String generateStabilityVideo(
            @ToolParam(description = "Absolute path to a local PNG/JPG image (1024x576, 576x1024, or 768x768)") String imagePath,
            @ToolParam(description = "Seed for reproducibility (0 = random)") Integer seed,
            @ToolParam(description = "CFG scale 0-10 (default 1.8)") Double cfgScale,
            @ToolParam(description = "Motion bucket id 1-255 (default 127, more = more motion)") Integer motionBucketId) {
        if (!configured()) return notConfig();
        if (imagePath == null || imagePath.isBlank()) return "Provide an image path.";
        Path img = Path.of(imagePath);
        if (!Files.exists(img)) return "Image not found: " + imagePath;
        notifier.notify("Submitting Stability video job...");
        try {
            String boundary = "----mins" + System.currentTimeMillis();
            byte[] imageBytes = Files.readAllBytes(img);
            String filename = img.getFileName().toString();
            String contentType = filename.toLowerCase().endsWith(".jpg") || filename.toLowerCase().endsWith(".jpeg")
                    ? "image/jpeg" : "image/png";
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            bos.write(("--" + boundary + "\r\n").getBytes());
            bos.write(("Content-Disposition: form-data; name=\"image\"; filename=\"" + filename + "\"\r\n").getBytes());
            bos.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes());
            bos.write(imageBytes);
            bos.write("\r\n".getBytes());
            int s = seed != null ? seed : 0;
            double cfg = cfgScale != null ? cfgScale : 1.8;
            int motion = motionBucketId != null ? motionBucketId : 127;
            bos.write(field(boundary, "seed", String.valueOf(s)));
            bos.write(field(boundary, "cfg_scale", String.valueOf(cfg)));
            bos.write(field(boundary, "motion_bucket_id", String.valueOf(motion)));
            bos.write(("--" + boundary + "--\r\n").getBytes());

            HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(BASE + "/image-to-video"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bos.toByteArray())).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("[Stability] HTTP {}: {}", resp.statusCode(), truncate(resp.body(), 300));
                return "Stability rejected (HTTP " + resp.statusCode() + "): " + truncate(resp.body(), 250);
            }
            String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(resp.body()).path("id").asText("");
            return "🎬 Stability SVD job submitted. id: " + id + "\nCheck with getStabilityVideoStatus('" + id + "') in ~60s.";
        } catch (Exception e) {
            log.warn("[Stability] submit failed: {}", e.getMessage());
            return "Failed to submit Stability video: " + e.getMessage();
        }
    }

    @Tool(description = "Check status of a Stability SVD generation. Returns a base64 MP4 when done (saved to outputPath).")
    public String getStabilityVideoStatus(
            @ToolParam(description = "Stability generation id") String id,
            @ToolParam(description = "Absolute path where the resulting MP4 should be saved if ready") String outputPath) {
        if (!configured()) return notConfig();
        if (id == null || id.isBlank()) return "Provide an id.";
        try {
            HttpResponse<byte[]> resp = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(BASE + "/image-to-video/result/" + id))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "video/*")
                    .timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 202) return "⏳ Stability still processing. Check again in ~30s.";
            if (resp.statusCode() / 100 != 2)
                return "Status check failed (HTTP " + resp.statusCode() + "): " + new String(resp.body());
            if (outputPath == null || outputPath.isBlank())
                return "✅ Video ready (" + resp.body().length + " bytes). Supply outputPath to save.";
            Path out = Path.of(outputPath);
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            Files.write(out, resp.body());
            return "✅ Stability video saved to " + out + " (" + resp.body().length + " bytes)";
        } catch (Exception e) {
            return "Failed to check Stability status: " + e.getMessage();
        }
    }

    private static byte[] field(String boundary, String name, String value) {
        return ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n").getBytes();
    }

    private boolean configured() { return apiKey != null && !apiKey.isBlank(); }
    private String notConfig() { return "Stability AI API key not set. Add it in Setup → Video generation."; }
    private static String truncate(String s, int n) { return s == null ? "" : (s.length() > n ? s.substring(0, n) + "…" : s); }
}
