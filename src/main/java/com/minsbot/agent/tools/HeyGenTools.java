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

/**
 * HeyGen AI — text-to-video with AI avatars. Requires an API key set in Setup
 * under {@code app.heygen.api-key}.
 *
 * <p>Docs: https://docs.heygen.com/reference
 */
@Component
public class HeyGenTools {

    private static final Logger log = LoggerFactory.getLogger(HeyGenTools.class);
    private static final String BASE_V1 = "https://api.heygen.com/v1";
    private static final String BASE_V2 = "https://api.heygen.com/v2";

    @Value("${app.heygen.api-key:}")
    private String apiKey;

    @Value("${app.heygen.default-avatar-id:}")
    private String defaultAvatarId;

    @Value("${app.heygen.default-voice-id:}")
    private String defaultVoiceId;

    private final ToolExecutionNotifier notifier;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    public HeyGenTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    // ─── Listings ───────────────────────────────────────────────────

    @Tool(description = "List available HeyGen avatars you can use in video generation. "
            + "Returns name + avatar_id for each. Use when the user says 'list heygen avatars', "
            + "'show me available avatars', 'what avatars can I use'.")
    public String listHeygenAvatars() {
        if (!isConfigured()) return notConfigured();
        notifier.notify("Fetching HeyGen avatars...");
        JsonNode root = getJson(BASE_V2 + "/avatars");
        if (root == null) return "Failed to fetch avatars.";
        JsonNode avatars = root.path("data").path("avatars");
        if (!avatars.isArray() || avatars.isEmpty()) return "No avatars found on your account.";
        StringBuilder sb = new StringBuilder("HeyGen avatars (").append(avatars.size()).append("):\n\n");
        int count = 0;
        for (JsonNode a : avatars) {
            count++;
            if (count > 25) { sb.append("…and ").append(avatars.size() - 25).append(" more."); break; }
            String name = a.path("avatar_name").asText(a.path("name").asText("?"));
            String id = a.path("avatar_id").asText("?");
            String gender = a.path("gender").asText("");
            sb.append("• ").append(name);
            if (!gender.isBlank()) sb.append(" (").append(gender).append(")");
            sb.append("\n  id: ").append(id).append("\n");
        }
        return sb.toString().trim();
    }

    @Tool(description = "List available HeyGen voices you can use for video generation. "
            + "Use when the user says 'list heygen voices', 'what voices can I use', 'show me heygen voices'.")
    public String listHeygenVoices(
            @ToolParam(description = "Optional language filter, e.g. 'English', 'Spanish'") String languageFilter) {
        if (!isConfigured()) return notConfigured();
        notifier.notify("Fetching HeyGen voices...");
        JsonNode root = getJson(BASE_V2 + "/voices");
        if (root == null) return "Failed to fetch voices.";
        JsonNode voices = root.path("data").path("voices");
        if (!voices.isArray() || voices.isEmpty()) return "No voices found.";
        String filter = languageFilter != null ? languageFilter.trim().toLowerCase() : "";
        StringBuilder sb = new StringBuilder("HeyGen voices:\n\n");
        int count = 0;
        for (JsonNode v : voices) {
            String lang = v.path("language").asText("");
            if (!filter.isEmpty() && !lang.toLowerCase().contains(filter)) continue;
            count++;
            if (count > 30) { sb.append("…and more — narrow with a language filter."); break; }
            String name = v.path("name").asText("?");
            String id = v.path("voice_id").asText("?");
            String gender = v.path("gender").asText("");
            sb.append("• ").append(name);
            if (!gender.isBlank()) sb.append(" (").append(gender).append(")");
            if (!lang.isBlank()) sb.append(" · ").append(lang);
            sb.append("\n  id: ").append(id).append("\n");
        }
        return count == 0 ? "No voices matched the filter." : sb.toString().trim();
    }

    // ─── Generation ─────────────────────────────────────────────────

    @Tool(description = "Generate a talking-head video with HeyGen using an AI avatar speaking your text. "
            + "Returns a video_id you can poll for status + the final video URL. "
            + "USE THIS when the user says 'make a heygen video of me saying X', 'create an AI avatar video', "
            + "'generate a talking head video'. Uses default avatar + voice from Setup if none provided.")
    public String generateHeygenVideo(
            @ToolParam(description = "Text the avatar should say (max ~1500 chars)") String text,
            @ToolParam(description = "Avatar ID (from listHeygenAvatars). Blank = use default from Setup.") String avatarId,
            @ToolParam(description = "Voice ID (from listHeygenVoices). Blank = use default from Setup.") String voiceId,
            @ToolParam(description = "Video width in pixels (e.g. 1280). Default 1280.") Integer width,
            @ToolParam(description = "Video height in pixels (e.g. 720). Default 720.") Integer height) {
        if (!isConfigured()) return notConfigured();
        if (text == null || text.isBlank()) return "Please provide the text the avatar should say.";

        String avatar = (avatarId != null && !avatarId.isBlank()) ? avatarId : defaultAvatarId;
        String voice  = (voiceId  != null && !voiceId.isBlank())  ? voiceId  : defaultVoiceId;
        if (avatar.isBlank()) return "No avatar specified. Set app.heygen.default-avatar-id in Setup or pass an avatarId. Run listHeygenAvatars to find IDs.";
        if (voice.isBlank())  return "No voice specified. Set app.heygen.default-voice-id in Setup or pass a voiceId. Run listHeygenVoices to find IDs.";

        int w = width  != null && width  > 0 ? width  : 1280;
        int h = height != null && height > 0 ? height : 720;
        String trimmed = text.length() > 1500 ? text.substring(0, 1500) : text;

        notifier.notify("Submitting HeyGen video job...");
        try {
            Map<String, Object> body = Map.of(
                    "video_inputs", java.util.List.of(Map.of(
                            "character", Map.of(
                                    "type", "avatar",
                                    "avatar_id", avatar,
                                    "avatar_style", "normal"),
                            "voice", Map.of(
                                    "type", "text",
                                    "input_text", trimmed,
                                    "voice_id", voice),
                            "background", Map.of(
                                    "type", "color",
                                    "value", "#f6f6fc"))),
                    "dimension", Map.of("width", w, "height", h));
            String bodyJson = mapper.writeValueAsString(body);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_V2 + "/video/generate"))
                    .header("X-Api-Key", apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("[HeyGen] generate HTTP {}: {}", resp.statusCode(),
                        truncate(resp.body(), 300));
                return "HeyGen rejected the job (HTTP " + resp.statusCode() + "): "
                        + truncate(resp.body(), 250);
            }
            JsonNode root = mapper.readTree(resp.body());
            String videoId = root.path("data").path("video_id").asText("");
            if (videoId.isEmpty()) return "HeyGen response missing video_id: " + truncate(resp.body(), 200);
            return "🎬 HeyGen video job submitted. video_id: " + videoId
                    + "\nPoll with getHeygenVideoStatus('" + videoId + "') — videos usually take 30-90s.";
        } catch (Exception e) {
            log.warn("[HeyGen] generate failed: {}", e.getMessage());
            return "Failed to submit HeyGen video: " + e.getMessage();
        }
    }

    @Tool(description = "Check the status of a HeyGen video job. Returns 'processing', 'completed' + "
            + "download URL, or 'failed' + reason. Use after generateHeygenVideo.")
    public String getHeygenVideoStatus(
            @ToolParam(description = "video_id returned by generateHeygenVideo") String videoId) {
        if (!isConfigured()) return notConfigured();
        if (videoId == null || videoId.isBlank()) return "Provide a video_id.";
        notifier.notify("Checking HeyGen video status...");
        JsonNode root = getJson(BASE_V1 + "/video_status.get?video_id=" + videoId);
        if (root == null) return "Failed to fetch status.";
        JsonNode data = root.path("data");
        String status = data.path("status").asText("?");
        switch (status) {
            case "completed" -> {
                String url = data.path("video_url").asText("");
                String thumb = data.path("thumbnail_url").asText("");
                StringBuilder sb = new StringBuilder("✅ HeyGen video ready!\n\nDownload: ").append(url);
                if (!thumb.isBlank()) sb.append("\nThumbnail: ").append(thumb);
                Object duration = data.path("duration").asText(null);
                if (duration != null) sb.append("\nDuration: ").append(duration).append("s");
                return sb.toString();
            }
            case "failed" -> {
                String err = data.path("error").toString();
                return "❌ HeyGen job failed: " + err;
            }
            case "processing", "pending", "waiting" -> {
                return "⏳ Still processing (status=" + status + "). Check again in 20-30s.";
            }
            default -> {
                return "HeyGen status: " + status + " — raw: " + truncate(data.toString(), 200);
            }
        }
    }

    @Tool(description = "Show HeyGen account info: remaining credits / quota. "
            + "Use when the user says 'my heygen credits', 'how much heygen quota left'.")
    public String getHeygenQuota() {
        if (!isConfigured()) return notConfigured();
        notifier.notify("Checking HeyGen quota...");
        JsonNode root = getJson(BASE_V1 + "/user/remaining_quota");
        if (root == null) return "Failed to fetch quota.";
        JsonNode data = root.path("data");
        long remaining = data.path("remaining_quota").asLong(-1);
        if (remaining < 0) return "Quota info unavailable: " + truncate(data.toString(), 200);
        // HeyGen reports remaining seconds * 60 typically
        return "HeyGen remaining quota: " + remaining + " (seconds of video × 60).";
    }

    // ─── Internals ──────────────────────────────────────────────────

    private boolean isConfigured() { return apiKey != null && !apiKey.isBlank(); }

    private String notConfigured() {
        return "HeyGen not configured. Add your API key in Setup → Video AI (HeyGen), "
                + "or set app.heygen.api-key in application-secrets.properties.";
    }

    private JsonNode getJson(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-Api-Key", apiKey)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("[HeyGen] GET {} → HTTP {}: {}", url, resp.statusCode(),
                        truncate(resp.body(), 300));
                return null;
            }
            return mapper.readTree(resp.body());
        } catch (Exception e) {
            log.warn("[HeyGen] GET {} failed: {}", url, e.getMessage());
            return null;
        }
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }
}
