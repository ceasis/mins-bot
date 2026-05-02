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

/** D-ID — generates talking-head video from a photo + text. */
@Component
public class DIDVideoTools {

    private static final Logger log = LoggerFactory.getLogger(DIDVideoTools.class);
    private static final String BASE = "https://api.d-id.com";

    @Value("${app.did.api-key:}")
    private String apiKey;

    @Value("${app.did.default-voice:en-US-JennyNeural}")
    private String defaultVoice;

    private final ToolExecutionNotifier notifier;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public DIDVideoTools(ToolExecutionNotifier notifier) { this.notifier = notifier; }

    // @Tool removed - duplicate provider; canonical SoraVideoTools.
    public String generateDIDVideo(
            @ToolParam(description = "Public image URL of the person whose face should be animated") String sourceImageUrl,
            @ToolParam(description = "Text to speak") String scriptText,
            @ToolParam(description = "Voice name (e.g. 'en-US-JennyNeural'); optional") String voiceId) {
        if (!configured()) return notConfig();
        if (sourceImageUrl == null || sourceImageUrl.isBlank()) return "Provide a source image URL.";
        if (scriptText == null || scriptText.isBlank()) return "Provide script text.";
        notifier.notify("Submitting D-ID talk...");
        try {
            Map<String, Object> script = new HashMap<>();
            script.put("type", "text");
            script.put("input", scriptText);
            Map<String, Object> provider = new HashMap<>();
            provider.put("type", "microsoft");
            provider.put("voice_id", (voiceId != null && !voiceId.isBlank()) ? voiceId : defaultVoice);
            script.put("provider", provider);
            Map<String, Object> body = Map.of(
                    "source_url", sourceImageUrl,
                    "script", script);
            String json = mapper.writeValueAsString(body);
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(BASE + "/talks"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Basic " + apiKey)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("[D-ID] HTTP {}: {}", resp.statusCode(), truncate(resp.body(), 300));
                return "D-ID rejected (HTTP " + resp.statusCode() + "): " + truncate(resp.body(), 250);
            }
            String id = mapper.readTree(resp.body()).path("id").asText("");
            return "🎬 D-ID talk submitted. id: " + id + "\nCheck with getDIDTalk('" + id + "') in ~20s.";
        } catch (Exception e) {
            log.warn("[D-ID] submit failed: {}", e.getMessage());
            return "Failed to submit D-ID talk: " + e.getMessage();
        }
    }

    // @Tool removed - duplicate provider; canonical SoraVideoTools.
    public String getDIDTalk(@ToolParam(description = "D-ID talk id") String id) {
        if (!configured()) return notConfig();
        if (id == null || id.isBlank()) return "Provide an id.";
        try {
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(BASE + "/talks/" + id))
                    .header("Authorization", "Basic " + apiKey)
                    .timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2)
                return "Status check failed (HTTP " + resp.statusCode() + "): " + truncate(resp.body(), 250);
            JsonNode root = mapper.readTree(resp.body());
            String status = root.path("status").asText("");
            if ("done".equalsIgnoreCase(status)) return "✅ D-ID video ready: " + root.path("result_url").asText("");
            if ("error".equalsIgnoreCase(status) || "rejected".equalsIgnoreCase(status))
                return "❌ D-ID failed: " + root.path("error").path("description").asText("unknown");
            return "⏳ D-ID status: " + status + ". Check again in ~15s.";
        } catch (Exception e) {
            return "Failed to check D-ID status: " + e.getMessage();
        }
    }

    private boolean configured() { return apiKey != null && !apiKey.isBlank(); }
    private String notConfig() { return "D-ID API key not set. Add it in Setup → Video avatars."; }
    private static String truncate(String s, int n) { return s == null ? "" : (s.length() > n ? s.substring(0, n) + "…" : s); }
}
