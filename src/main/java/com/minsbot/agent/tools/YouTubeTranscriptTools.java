package com.minsbot.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the auto-generated or creator-provided transcript of a YouTube video
 * by parsing the watch page's embedded {@code ytInitialPlayerResponse} JSON and
 * fetching the caption track XML. No API key, no OAuth — just HTML scraping.
 * Ideal for "summarize this YouTube video" requests.
 */
@Component
public class YouTubeTranscriptTools {

    private static final Logger log = LoggerFactory.getLogger(YouTubeTranscriptTools.class);

    private static final Pattern VIDEO_ID_RE = Pattern.compile(
            "(?:youtu\\.be/|youtube\\.com/(?:watch\\?(?:.*&)?v=|embed/|shorts/|v/))([A-Za-z0-9_-]{11})");
    private static final Pattern PLAYER_RESP_RE = Pattern.compile(
            "ytInitialPlayerResponse\\s*=\\s*(\\{.+?\\});", Pattern.DOTALL);
    private static final Pattern XML_TEXT_RE = Pattern.compile(
            "<text[^>]*>(.*?)</text>", Pattern.DOTALL);

    private final ToolExecutionNotifier notifier;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    @Autowired(required = false)
    private ChatClient chatClient;

    public YouTubeTranscriptTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @Tool(description = "Get the transcript (captions) of a YouTube video as plain text. "
            + "Works for any public video with captions — auto-generated or creator-provided — without requiring an API key. "
            + "Use when the user says 'get transcript of this YouTube video', 'what does this video say', "
            + "'transcribe this youtube'. Accepts full URL or video ID. Returns the transcript text.")
    public String getYouTubeTranscript(
            @ToolParam(description = "YouTube video URL or 11-char video ID") String urlOrId,
            @ToolParam(description = "Language code (e.g. 'en', 'es'). Empty = first available track.") String language) {
        if (urlOrId == null || urlOrId.isBlank()) return "Provide a YouTube URL or video ID.";
        String videoId = extractVideoId(urlOrId.trim());
        if (videoId == null) return "Could not parse a YouTube video ID from: " + urlOrId;

        notifier.notify("Fetching transcript for " + videoId + "...");
        try {
            // 1. Fetch the watch page
            String watchHtml = fetch("https://www.youtube.com/watch?v=" + videoId);
            if (watchHtml == null) return "Could not load video page.";

            // 2. Extract ytInitialPlayerResponse JSON
            Matcher m = PLAYER_RESP_RE.matcher(watchHtml);
            if (!m.find()) return "Couldn't locate player response — YouTube page structure may have changed.";
            JsonNode root;
            try {
                root = mapper.readTree(m.group(1));
            } catch (Exception e) {
                return "Could not parse player response JSON.";
            }

            // 3. Find caption tracks
            JsonNode tracks = root.path("captions")
                    .path("playerCaptionsTracklistRenderer")
                    .path("captionTracks");
            if (!tracks.isArray() || tracks.isEmpty()) {
                return "This video has no captions available.";
            }

            // 4. Pick the track matching the requested language (or first)
            JsonNode chosen = null;
            String lang = (language != null && !language.isBlank()) ? language.trim().toLowerCase() : "";
            for (JsonNode t : tracks) {
                String code = t.path("languageCode").asText("");
                if (lang.isEmpty() || code.equalsIgnoreCase(lang)) { chosen = t; break; }
            }
            if (chosen == null) chosen = tracks.get(0);
            String baseUrl = chosen.path("baseUrl").asText(null);
            if (baseUrl == null) return "Caption track has no URL.";

            // 5. Fetch the caption XML and extract text
            String xml = fetch(baseUrl);
            if (xml == null) return "Could not download captions.";
            return parseCaptionXml(xml);
        } catch (Exception e) {
            log.warn("[YTTranscript] failed: {}", e.getMessage());
            return "Transcript fetch failed: " + e.getMessage();
        }
    }

    @Tool(description = "Summarize a YouTube video by fetching its transcript and asking the AI to produce "
            + "key points, timestamps, and conclusions. USE THIS when the user says 'summarize this YouTube video', "
            + "'TLDR this video', 'what's this video about', 'give me bullet points of this video'.")
    public String summarizeYouTubeVideo(
            @ToolParam(description = "YouTube video URL or 11-char video ID") String urlOrId,
            @ToolParam(description = "Optional focus — e.g. 'technical details' or 'investment advice only'. Empty for general.") String focus) {
        if (chatClient == null) return "AI not configured — cannot summarize.";
        String transcript = getYouTubeTranscript(urlOrId, "");
        if (transcript == null || transcript.startsWith("Could not") || transcript.startsWith("This video")
                || transcript.startsWith("Caption") || transcript.startsWith("Transcript fetch") || transcript.startsWith("Provide")) {
            return transcript;
        }
        // Truncate very long transcripts
        if (transcript.length() > 30000) transcript = transcript.substring(0, 30000) + "\n\n…[truncated]";

        notifier.notify("Summarizing video...");
        String focusHint = (focus != null && !focus.isBlank()) ? ("Focus: " + focus + ".\n\n") : "";
        try {
            return chatClient.prompt()
                    .system("Summarize YouTube transcripts concisely. Output: a 1-sentence TL;DR, "
                            + "then 3-6 bulleted key points, then a one-sentence conclusion. "
                            + "Preserve any specific numbers/dates/names. Do not invent content.")
                    .user(focusHint + "Transcript:\n\n" + transcript)
                    .call().content();
        } catch (Exception e) {
            return "Summarization failed: " + e.getMessage();
        }
    }

    // ─── Internals ──────────────────────────────────────────────

    private static String extractVideoId(String s) {
        if (s.length() == 11 && s.matches("[A-Za-z0-9_-]{11}")) return s;
        Matcher m = VIDEO_ID_RE.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private String fetch(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .timeout(Duration.ofSeconds(20))
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return resp.statusCode() / 100 == 2 ? resp.body() : null;
        } catch (Exception e) {
            log.debug("[YTTranscript] fetch {} failed: {}", url, e.getMessage());
            return null;
        }
    }

    private String parseCaptionXml(String xml) {
        StringBuilder out = new StringBuilder();
        Matcher m = XML_TEXT_RE.matcher(xml);
        while (m.find()) {
            String seg = m.group(1)
                    .replace("&amp;", "&")
                    .replace("&lt;", "<").replace("&gt;", ">")
                    .replace("&quot;", "\"").replace("&#39;", "'");
            try { seg = URLDecoder.decode(seg, StandardCharsets.UTF_8); } catch (Exception ignored) {}
            seg = seg.replaceAll("<[^>]+>", "").trim();
            if (!seg.isEmpty()) out.append(seg).append(' ');
        }
        String text = out.toString().replaceAll("\\s+", " ").trim();
        return text.isEmpty() ? "Transcript was empty after parsing." : text;
    }
}
