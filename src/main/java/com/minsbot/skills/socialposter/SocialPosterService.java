package com.minsbot.skills.socialposter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Posts text to Bluesky, Mastodon, or a generic webhook. Each platform is
 * independent — call /post and pass which platforms to target.
 *
 * NOTE: X (Twitter) and LinkedIn are NOT implemented. Both require OAuth2
 * authorization flows + approved app credentials and are too involved for
 * a token-only skill. Use the webhook fan-out (Zapier, Make, n8n) for those
 * platforms today, or supply a TODO for a future skill.
 */
@Service
public class SocialPosterService {

    private final SocialPosterConfig.SocialPosterProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;

    public SocialPosterService(SocialPosterConfig.SocialPosterProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    public Map<String, Object> post(String text, List<String> platforms) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("text required");
        if (platforms == null || platforms.isEmpty())
            platforms = autodetectPlatforms();

        Map<String, Object> results = new LinkedHashMap<>();
        for (String p : platforms) {
            String key = p.toLowerCase(Locale.ROOT);
            try {
                results.put(key, switch (key) {
                    case "bluesky" -> postBluesky(text);
                    case "mastodon" -> postMastodon(text);
                    case "webhook" -> postWebhook(text);
                    case "x", "twitter" -> Map.of("ok", false,
                            "error", "X/Twitter requires OAuth2 — not implemented. Use webhook fan-out via Zapier/Make.");
                    case "linkedin" -> Map.of("ok", false,
                            "error", "LinkedIn requires OAuth2 — not implemented. Use webhook fan-out.");
                    default -> Map.of("ok", false, "error", "unknown platform: " + key);
                });
            } catch (Exception e) {
                results.put(key, Map.of("ok", false, "error", e.getMessage()));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("skill", "socialposter");
        out.put("postedAt", Instant.now().toString());
        out.put("textLength", text.length());
        out.put("results", results);
        return out;
    }

    private List<String> autodetectPlatforms() {
        List<String> p = new ArrayList<>();
        if (!props.getBlueskyHandle().isBlank() && !props.getBlueskyPassword().isBlank()) p.add("bluesky");
        if (!props.getMastodonInstance().isBlank() && !props.getMastodonToken().isBlank()) p.add("mastodon");
        if (!props.getWebhookUrl().isBlank()) p.add("webhook");
        return p;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postBluesky(String text) throws Exception {
        if (props.getBlueskyHandle().isBlank() || props.getBlueskyPassword().isBlank())
            return Map.of("ok", false, "error", "Bluesky handle/password not configured");
        if (text.length() > 300) text = text.substring(0, 297) + "...";

        // 1. Create session
        Map<String, Object> sessionReq = Map.of(
                "identifier", props.getBlueskyHandle(),
                "password", props.getBlueskyPassword());
        HttpRequest auth = HttpRequest.newBuilder()
                .uri(URI.create(props.getBlueskyHost() + "/xrpc/com.atproto.server.createSession"))
                .timeout(Duration.ofMillis(props.getTimeoutMs()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(sessionReq)))
                .build();
        HttpResponse<String> authResp = http.send(auth, HttpResponse.BodyHandlers.ofString());
        if (authResp.statusCode() / 100 != 2)
            return Map.of("ok", false, "error", "auth HTTP " + authResp.statusCode() + ": " + authResp.body());
        Map<String, Object> session = mapper.readValue(authResp.body(), Map.class);
        String accessJwt = (String) session.get("accessJwt");
        String did = (String) session.get("did");

        // 2. Post record
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("$type", "app.bsky.feed.post");
        record.put("text", text);
        record.put("createdAt", Instant.now().toString());
        Map<String, Object> body = Map.of("repo", did, "collection", "app.bsky.feed.post", "record", record);

        HttpRequest postReq = HttpRequest.newBuilder()
                .uri(URI.create(props.getBlueskyHost() + "/xrpc/com.atproto.repo.createRecord"))
                .timeout(Duration.ofMillis(props.getTimeoutMs()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + accessJwt)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
        HttpResponse<String> r = http.send(postReq, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2)
            return Map.of("ok", false, "error", "HTTP " + r.statusCode() + ": " + r.body());
        Map<String, Object> result = mapper.readValue(r.body(), Map.class);
        return Map.of("ok", true, "uri", result.getOrDefault("uri", ""), "cid", result.getOrDefault("cid", ""));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postMastodon(String text) throws Exception {
        if (props.getMastodonInstance().isBlank() || props.getMastodonToken().isBlank())
            return Map.of("ok", false, "error", "Mastodon instance/token not configured");
        if (text.length() > 500) text = text.substring(0, 497) + "...";

        String url = props.getMastodonInstance().replaceAll("/$", "") + "/api/v1/statuses";
        Map<String, Object> body = Map.of("status", text);
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .timeout(Duration.ofMillis(props.getTimeoutMs()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + props.getMastodonToken())
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2)
            return Map.of("ok", false, "error", "HTTP " + r.statusCode() + ": " + truncate(r.body(), 200));
        Map<String, Object> result = mapper.readValue(r.body(), Map.class);
        return Map.of("ok", true, "id", result.getOrDefault("id", ""), "url", result.getOrDefault("url", ""));
    }

    private Map<String, Object> postWebhook(String text) throws Exception {
        if (props.getWebhookUrl().isBlank())
            return Map.of("ok", false, "error", "Webhook URL not configured");
        Map<String, Object> body = Map.of("content", text, "text", text); // works for Discord + Slack
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(props.getWebhookUrl()))
                .timeout(Duration.ofMillis(props.getTimeoutMs()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        return r.statusCode() / 100 == 2
                ? Map.of("ok", true, "status", r.statusCode())
                : Map.of("ok", false, "error", "HTTP " + r.statusCode() + ": " + truncate(r.body(), 200));
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
