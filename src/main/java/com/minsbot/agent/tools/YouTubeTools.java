package com.minsbot.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minsbot.GoogleIntegrationOAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * YouTube Data API tools. Requires the "youtube" OAuth integration connected
 * in the Integrations tab (youtube.readonly scope).
 */
@Component
public class YouTubeTools {

    private static final Logger log = LoggerFactory.getLogger(YouTubeTools.class);
    private static final String YT_API = "https://www.googleapis.com/youtube/v3";

    private final GoogleIntegrationOAuthService oauthService;
    private final ToolExecutionNotifier notifier;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public YouTubeTools(GoogleIntegrationOAuthService oauthService, ToolExecutionNotifier notifier) {
        this.oauthService = oauthService;
        this.notifier = notifier;
    }

    // ───────────────────────────────────────────────────────────────────
    //  My channel
    // ───────────────────────────────────────────────────────────────────

    @Tool(description = "Get the signed-in user's own YouTube channel info: name, ID, subscriber count, total video count, total views. Requires YouTube integration connected.")
    public String getMyYouTubeChannel() {
        notifier.notify("Fetching YouTube channel info...");
        String token = token();
        if (token == null) return notConnected();

        String url = YT_API + "/channels?part=snippet,statistics&mine=true";
        JsonNode root = get(url, token);
        if (root == null) return "Failed to fetch channel info.";

        JsonNode items = root.path("items");
        if (!items.isArray() || items.isEmpty()) return "No YouTube channel found for this account.";

        JsonNode ch = items.get(0);
        String title = ch.path("snippet").path("title").asText("?");
        String id = ch.path("id").asText("?");
        String desc = ch.path("snippet").path("description").asText("").trim();
        JsonNode stats = ch.path("statistics");
        String subs = stats.path("subscriberCount").asText("?");
        String videos = stats.path("videoCount").asText("?");
        String views = stats.path("viewCount").asText("?");

        StringBuilder sb = new StringBuilder();
        sb.append("Channel: ").append(title).append(" (ID: ").append(id).append(")\n");
        sb.append("Subscribers: ").append(subs).append("\n");
        sb.append("Videos: ").append(videos).append("\n");
        sb.append("Total views: ").append(views);
        if (!desc.isEmpty()) sb.append("\nDescription: ").append(truncate(desc, 300));
        return sb.toString();
    }

    // ───────────────────────────────────────────────────────────────────
    //  Search
    // ───────────────────────────────────────────────────────────────────

    @Tool(description = "Search YouTube for videos. Returns title, channel, published date, and video ID for each result.")
    public String searchYouTubeVideos(
            @ToolParam(description = "Search query") String query,
            @ToolParam(description = "Max results (1-25, default 10)") Integer maxResults) {
        int max = clamp(maxResults != null ? maxResults : 10, 1, 25);
        notifier.notify("Searching YouTube: " + query);
        String token = token();
        if (token == null) return notConnected();

        String url = YT_API + "/search?part=snippet&type=video&q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&maxResults=" + max;
        JsonNode root = get(url, token);
        if (root == null) return "YouTube search failed.";

        return formatSearchResults(root, "Search results for \"" + query + "\"");
    }

    // ───────────────────────────────────────────────────────────────────
    //  Channel uploads
    // ───────────────────────────────────────────────────────────────────

    @Tool(description = "Get the most recent uploads from a specific YouTube channel by its channel ID or handle (e.g. @mrbeast).")
    public String getChannelRecentUploads(
            @ToolParam(description = "Channel ID (starts with UC...) or handle (starts with @)") String channelIdOrHandle,
            @ToolParam(description = "Max videos to return (1-25, default 10)") Integer maxResults) {
        int max = clamp(maxResults != null ? maxResults : 10, 1, 25);
        notifier.notify("Fetching channel uploads...");
        String token = token();
        if (token == null) return notConnected();

        String channelId = resolveChannelId(channelIdOrHandle, token);
        if (channelId == null) return "Channel not found: " + channelIdOrHandle;

        // Get the uploads playlist ID
        String chUrl = YT_API + "/channels?part=contentDetails&id=" + channelId;
        JsonNode chRoot = get(chUrl, token);
        if (chRoot == null) return "Failed to fetch channel details.";
        JsonNode items = chRoot.path("items");
        if (!items.isArray() || items.isEmpty()) return "Channel not found: " + channelIdOrHandle;

        String uploadsPlaylist = items.get(0).path("contentDetails").path("relatedPlaylists").path("uploads").asText("");
        if (uploadsPlaylist.isEmpty()) return "No uploads playlist for this channel.";

        // Fetch playlist items
        String plUrl = YT_API + "/playlistItems?part=snippet&playlistId=" + uploadsPlaylist + "&maxResults=" + max;
        JsonNode plRoot = get(plUrl, token);
        if (plRoot == null) return "Failed to fetch uploads.";

        StringBuilder sb = new StringBuilder("Recent uploads:\n\n");
        JsonNode plItems = plRoot.path("items");
        int count = 0;
        for (JsonNode v : plItems) {
            JsonNode snip = v.path("snippet");
            String title = snip.path("title").asText("?");
            String date = snip.path("publishedAt").asText("").substring(0, Math.min(10, snip.path("publishedAt").asText("").length()));
            String vid = snip.path("resourceId").path("videoId").asText("");
            count++;
            sb.append(count).append(". ").append(title)
              .append("\n   Published: ").append(date).append(" · ID: ").append(vid).append("\n");
        }
        if (count == 0) return "No uploads found.";
        return sb.toString().trim();
    }

    // ───────────────────────────────────────────────────────────────────
    //  Subscriptions
    // ───────────────────────────────────────────────────────────────────

    @Tool(description = "List channels the user is subscribed to on YouTube, with subscriber counts.")
    public String listMyYouTubeSubscriptions(
            @ToolParam(description = "Max subscriptions to return (1-50, default 20)") Integer maxResults) {
        int max = clamp(maxResults != null ? maxResults : 20, 1, 50);
        notifier.notify("Fetching YouTube subscriptions...");
        String token = token();
        if (token == null) return notConnected();

        String url = YT_API + "/subscriptions?part=snippet&mine=true&order=alphabetical&maxResults=" + max;
        JsonNode root = get(url, token);
        if (root == null) return "Failed to fetch subscriptions.";

        JsonNode items = root.path("items");
        if (!items.isArray() || items.isEmpty()) return "You have no YouTube subscriptions.";

        StringBuilder sb = new StringBuilder("Your YouTube subscriptions:\n");
        int count = 0;
        for (JsonNode s : items) {
            JsonNode snip = s.path("snippet");
            String title = snip.path("title").asText("?");
            String desc = snip.path("description").asText("").trim();
            count++;
            sb.append(count).append(". ").append(title);
            if (!desc.isEmpty()) sb.append(" — ").append(truncate(desc, 80));
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    // ───────────────────────────────────────────────────────────────────
    //  Video details
    // ───────────────────────────────────────────────────────────────────

    @Tool(description = "Get detailed stats for a YouTube video by video ID: title, channel, views, likes, comments, duration, description.")
    public String getYouTubeVideoDetails(
            @ToolParam(description = "YouTube video ID (e.g. 'dQw4w9WgXcQ')") String videoId) {
        notifier.notify("Fetching video details...");
        String token = token();
        if (token == null) return notConnected();

        String url = YT_API + "/videos?part=snippet,statistics,contentDetails&id=" + videoId;
        JsonNode root = get(url, token);
        if (root == null) return "Failed to fetch video details.";

        JsonNode items = root.path("items");
        if (!items.isArray() || items.isEmpty()) return "Video not found: " + videoId;

        JsonNode v = items.get(0);
        JsonNode snip = v.path("snippet");
        JsonNode stats = v.path("statistics");
        String duration = v.path("contentDetails").path("duration").asText("");

        StringBuilder sb = new StringBuilder();
        sb.append("Title: ").append(snip.path("title").asText("?")).append("\n");
        sb.append("Channel: ").append(snip.path("channelTitle").asText("?")).append("\n");
        sb.append("Published: ").append(snip.path("publishedAt").asText("").substring(0, 10)).append("\n");
        sb.append("Duration: ").append(duration).append("\n");
        sb.append("Views: ").append(stats.path("viewCount").asText("?")).append("\n");
        sb.append("Likes: ").append(stats.path("likeCount").asText("?")).append("\n");
        sb.append("Comments: ").append(stats.path("commentCount").asText("?")).append("\n");
        String desc = snip.path("description").asText("").trim();
        if (!desc.isEmpty()) sb.append("Description: ").append(truncate(desc, 400));
        return sb.toString().trim();
    }

    // ───────────────────────────────────────────────────────────────────
    //  Trending
    // ───────────────────────────────────────────────────────────────────

    @Tool(description = "Get currently trending videos on YouTube (region-specific).")
    public String getTrendingYouTubeVideos(
            @ToolParam(description = "ISO country code, e.g. 'US', 'PH', 'GB' (default 'US')") String regionCode,
            @ToolParam(description = "Max results (1-25, default 10)") Integer maxResults) {
        int max = clamp(maxResults != null ? maxResults : 10, 1, 25);
        String region = (regionCode == null || regionCode.isBlank()) ? "US" : regionCode.trim().toUpperCase();
        notifier.notify("Fetching trending on YouTube " + region + "...");
        String token = token();
        if (token == null) return notConnected();

        String url = YT_API + "/videos?part=snippet,statistics&chart=mostPopular&regionCode="
                + region + "&maxResults=" + max;
        JsonNode root = get(url, token);
        if (root == null) return "Failed to fetch trending.";

        JsonNode items = root.path("items");
        if (!items.isArray() || items.isEmpty()) return "No trending videos for region " + region;

        StringBuilder sb = new StringBuilder("Trending on YouTube (" + region + "):\n\n");
        int count = 0;
        for (JsonNode v : items) {
            JsonNode snip = v.path("snippet");
            JsonNode stats = v.path("statistics");
            count++;
            sb.append(count).append(". ").append(snip.path("title").asText("?"))
              .append(" — ").append(snip.path("channelTitle").asText("?"))
              .append(" · ").append(stats.path("viewCount").asText("?")).append(" views\n");
        }
        return sb.toString().trim();
    }

    // ───────────────────────────────────────────────────────────────────
    //  Internals
    // ───────────────────────────────────────────────────────────────────

    private String token() {
        return oauthService.getValidAccessToken("youtube");
    }

    private String notConnected() {
        return "YouTube not connected. Go to the Integrations tab and click 'Sign in with Google' on the YouTube card.";
    }

    private JsonNode get(String url, String token) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("[YouTube] HTTP {}: {}", resp.statusCode(), truncate(resp.body(), 300));
                return null;
            }
            return mapper.readTree(resp.body());
        } catch (Exception e) {
            log.warn("[YouTube] Request failed: {}", e.getMessage());
            return null;
        }
    }

    private String resolveChannelId(String input, String token) {
        if (input == null || input.isBlank()) return null;
        String s = input.trim();
        if (s.startsWith("UC") && s.length() > 20) return s;  // looks like a real channel ID
        if (s.startsWith("@")) {
            String url = YT_API + "/channels?part=id&forHandle=" + URLEncoder.encode(s, StandardCharsets.UTF_8);
            JsonNode r = get(url, token);
            if (r != null && r.path("items").isArray() && !r.path("items").isEmpty()) {
                return r.path("items").get(0).path("id").asText(null);
            }
        }
        // Fallback: treat as custom URL search
        String searchUrl = YT_API + "/search?part=snippet&type=channel&q="
                + URLEncoder.encode(s, StandardCharsets.UTF_8) + "&maxResults=1";
        JsonNode r = get(searchUrl, token);
        if (r != null && r.path("items").isArray() && !r.path("items").isEmpty()) {
            return r.path("items").get(0).path("snippet").path("channelId").asText(null);
        }
        return null;
    }

    private String formatSearchResults(JsonNode root, String heading) {
        JsonNode items = root.path("items");
        if (!items.isArray() || items.isEmpty()) return "No results.";
        StringBuilder sb = new StringBuilder(heading).append(":\n\n");
        int count = 0;
        for (JsonNode it : items) {
            JsonNode snip = it.path("snippet");
            String title = snip.path("title").asText("?");
            String channel = snip.path("channelTitle").asText("?");
            String date = snip.path("publishedAt").asText("");
            date = date.length() >= 10 ? date.substring(0, 10) : date;
            String vid = it.path("id").path("videoId").asText("");
            count++;
            sb.append(count).append(". ").append(title)
              .append("\n   ").append(channel).append(" · ").append(date);
            if (!vid.isEmpty()) sb.append(" · ID: ").append(vid);
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private int clamp(int v, int min, int max) {
        return Math.min(max, Math.max(min, v));
    }

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "…" : (s != null ? s : "");
    }
}
