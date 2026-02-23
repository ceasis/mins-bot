package com.minsbot.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto-detects music from audio memory transcriptions and saves identified songs
 * to ~/mins_bot_data/playlist_config.txt.
 *
 * <p>The playlist file has two sections:
 * <ul>
 *   <li><b>## Summary</b> — unique songs with play count, e.g. {@code - "Title" by Artist (3x)}</li>
 *   <li><b>## History</b> — every detection with timestamp, e.g. {@code - [2026-02-23 14:30] "Title" by Artist}</li>
 * </ul>
 *
 * <p>After each audio capture transcription, {@link AudioMemoryService} calls
 * {@link #classifyAndSave(String)} which makes a lightweight gpt-4o-mini call
 * to determine if the text contains music lyrics.</p>
 */
@Component
public class PlaylistService {

    private static final Logger log = LoggerFactory.getLogger(PlaylistService.class);

    private static final Path CONFIG_PATH =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "minsbot_config.txt");

    private static final Path PLAYLIST_PATH =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "playlist_config.txt");

    private static final String PLAYLIST_HEADER = """
            # Mins Bot Playlist
            Songs automatically detected from system audio.

            ## Summary

            ## History
            """;

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** Matches summary lines: - "Title" by Artist (3x) */
    private static final Pattern SUMMARY_PATTERN =
            Pattern.compile("^- \"(.+?)\" by (.+?) \\((\\d+)x\\)$");

    private static final String CLASSIFICATION_PROMPT =
            "You are a music detector. Given transcribed audio text, determine if it contains "
            + "song lyrics or music. If it IS music/song lyrics, respond ONLY with JSON: "
            + "{\"music\":true,\"title\":\"Song Title\",\"artist\":\"Artist Name\"} "
            + "If it is NOT music (speech, silence, noise, podcast, talking, etc), respond ONLY with JSON: "
            + "{\"music\":false}";

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url:https://api.openai.com}")
    private String baseUrl;

    // Config (mutable, reloaded at runtime)
    private volatile boolean enabled = true;

    private HttpClient httpClient;

    @PostConstruct
    void init() throws IOException {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        Files.createDirectories(PLAYLIST_PATH.getParent());
        if (!Files.exists(PLAYLIST_PATH)) {
            Files.writeString(PLAYLIST_PATH, PLAYLIST_HEADER, StandardCharsets.UTF_8);
        }

        loadConfigFromFile();

        boolean hasKey = apiKey != null && !apiKey.isBlank();
        log.info("[Playlist] Init — enabled={}, hasApiKey={}", enabled, hasKey);
    }

    public boolean isEnabled() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    // ═══ Music classification ═══

    /**
     * Classify a transcription and save if it is music.
     * Called by AudioMemoryService after each audio capture transcription.
     * Uses a lightweight gpt-4o-mini call (raw HTTP) to avoid polluting conversation memory.
     */
    public void classifyAndSave(String transcription) {
        if (!isEnabled()) return;
        if (transcription == null || transcription.length() < 10) return;

        try {
            String requestBody = "{\"model\":\"gpt-4o-mini\",\"temperature\":0,\"max_tokens\":100,\"messages\":["
                    + "{\"role\":\"system\",\"content\":\"" + escapeJson(CLASSIFICATION_PROMPT) + "\"},"
                    + "{\"role\":\"user\",\"content\":\"" + escapeJson(transcription) + "\"}]}";

            String url = baseUrl.endsWith("/")
                    ? baseUrl + "v1/chat/completions"
                    : baseUrl + "/v1/chat/completions";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.debug("[Playlist] Classification API returned HTTP {}", response.statusCode());
                return;
            }

            String content = extractContent(response.body());
            if (content.isBlank()) return;

            boolean isMusic = content.contains("\"music\":true") || content.contains("\"music\": true");
            if (!isMusic) return;

            String title = extractJsonField(content, "title");
            String artist = extractJsonField(content, "artist");
            if (title == null || title.isBlank()) return;
            if (artist == null || artist.isBlank()) artist = "Unknown";

            String result = addToPlaylist(title, artist);
            log.info("[Playlist] {}", result);

        } catch (Exception e) {
            log.debug("[Playlist] Classification failed: {}", e.getMessage());
        }
    }

    // ═══ Playlist CRUD ═══

    /**
     * Add a song to the playlist. Appends to History (always) and updates Summary
     * (increments count if existing, adds new entry if first time).
     */
    public synchronized String addToPlaylist(String title, String artist) {
        try {
            PlaylistData data = readPlaylistData();

            // Update summary: find existing or add new
            String titleLower = title.toLowerCase().trim();
            boolean found = false;
            for (int i = 0; i < data.summaryEntries.size(); i++) {
                SummaryEntry entry = data.summaryEntries.get(i);
                if (entry.title.toLowerCase().equals(titleLower)) {
                    data.summaryEntries.set(i, new SummaryEntry(entry.title, entry.artist, entry.count + 1));
                    found = true;
                    break;
                }
            }
            if (!found) {
                data.summaryEntries.add(new SummaryEntry(title, artist, 1));
            }

            // Always append to history
            String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
            data.historyLines.add("- [" + timestamp + "] \"" + title + "\" by " + artist);

            writePlaylistData(data);

            int count = found ? data.summaryEntries.stream()
                    .filter(e -> e.title.toLowerCase().equals(titleLower))
                    .findFirst().map(e -> e.count).orElse(1) : 1;
            return found
                    ? "Detected again: \"" + title + "\" by " + artist + " (" + count + "x)"
                    : "Added to playlist: \"" + title + "\" by " + artist;

        } catch (IOException e) {
            return "Failed to update playlist: " + e.getMessage();
        }
    }

    /**
     * Remove a song from the playlist by title (case-insensitive partial match).
     * Removes from both Summary and History.
     */
    public synchronized String removeFromPlaylist(String title) {
        try {
            if (!Files.exists(PLAYLIST_PATH)) return "Playlist is empty.";
            PlaylistData data = readPlaylistData();
            String titleLower = title.toLowerCase().trim();

            boolean removed = data.summaryEntries.removeIf(
                    e -> e.title.toLowerCase().contains(titleLower));
            removed |= data.historyLines.removeIf(
                    line -> line.toLowerCase().contains(titleLower));

            if (!removed) return "Song not found in playlist: \"" + title + "\"";

            writePlaylistData(data);
            return "Removed \"" + title + "\" from playlist.";

        } catch (IOException e) {
            return "Failed to remove from playlist: " + e.getMessage();
        }
    }

    /** Returns the full playlist content, or a message if empty. */
    public String getPlaylist() {
        try {
            if (!Files.exists(PLAYLIST_PATH)) return "No playlist yet. Play some music and it will be auto-detected!";
            String content = Files.readString(PLAYLIST_PATH, StandardCharsets.UTF_8).trim();
            if (content.isBlank() || content.equals(PLAYLIST_HEADER.trim())) {
                return "Playlist is empty. Play some music and it will be auto-detected!";
            }
            return content;
        } catch (IOException e) {
            return "Failed to read playlist: " + e.getMessage();
        }
    }

    /** Clears all songs, keeping the section headers. */
    public synchronized String clearPlaylist() {
        try {
            Files.writeString(PLAYLIST_PATH, PLAYLIST_HEADER, StandardCharsets.UTF_8);
            return "Playlist cleared.";
        } catch (IOException e) {
            return "Failed to clear playlist: " + e.getMessage();
        }
    }

    // ═══ Config ═══

    public void reloadConfig() {
        loadConfigFromFile();
        log.info("[Playlist] Config reloaded — enabled={}", enabled);
    }

    private void loadConfigFromFile() {
        try {
            if (!Files.exists(CONFIG_PATH)) return;
            List<String> lines = Files.readAllLines(CONFIG_PATH, StandardCharsets.UTF_8);
            boolean inSection = false;
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("## ")) {
                    inSection = trimmed.toLowerCase().startsWith("## playlist");
                    continue;
                }
                if (!inSection || !trimmed.startsWith("- ")) continue;

                String kv = trimmed.substring(2).trim();
                int colon = kv.indexOf(':');
                if (colon < 0) continue;
                String key = kv.substring(0, colon).trim().toLowerCase();
                String val = kv.substring(colon + 1).trim().toLowerCase();

                if ("enabled".equals(key)) {
                    enabled = "true".equals(val) || "yes".equals(val);
                }
            }
        } catch (IOException e) {
            log.warn("[Playlist] Failed to load config: {}", e.getMessage());
        }
    }

    // ═══ Playlist file read/write ═══

    private record SummaryEntry(String title, String artist, int count) {}

    private static class PlaylistData {
        final List<SummaryEntry> summaryEntries = new ArrayList<>();
        final List<String> historyLines = new ArrayList<>();
    }

    /** Parse playlist_config.txt into structured data. */
    private PlaylistData readPlaylistData() throws IOException {
        PlaylistData data = new PlaylistData();
        if (!Files.exists(PLAYLIST_PATH)) return data;

        List<String> lines = Files.readAllLines(PLAYLIST_PATH, StandardCharsets.UTF_8);
        String section = "";
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("## ")) {
                section = trimmed.toLowerCase();
                continue;
            }
            if (!trimmed.startsWith("- ")) continue;

            if (section.contains("summary")) {
                Matcher m = SUMMARY_PATTERN.matcher(trimmed);
                if (m.matches()) {
                    data.summaryEntries.add(new SummaryEntry(
                            m.group(1), m.group(2).trim(), Integer.parseInt(m.group(3))));
                }
            } else if (section.contains("history")) {
                data.historyLines.add(trimmed);
            }
        }
        return data;
    }

    /** Write structured data back to playlist_config.txt. */
    private void writePlaylistData(PlaylistData data) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Mins Bot Playlist\n");
        sb.append("Songs automatically detected from system audio.\n\n");

        sb.append("## Summary\n");
        for (SummaryEntry entry : data.summaryEntries) {
            sb.append("- \"").append(entry.title).append("\" by ")
              .append(entry.artist).append(" (").append(entry.count).append("x)\n");
        }

        sb.append("\n## History\n");
        for (String line : data.historyLines) {
            sb.append(line).append("\n");
        }

        Files.writeString(PLAYLIST_PATH, sb.toString(), StandardCharsets.UTF_8);
    }

    // ═══ JSON helpers (manual, same pattern as ToolClassifierService) ═══

    private static String extractContent(String json) {
        int choicesIdx = json.indexOf("\"choices\"");
        if (choicesIdx < 0) return "";
        int contentIdx = json.indexOf("\"content\":", choicesIdx);
        if (contentIdx < 0) return "";

        int start = json.indexOf('"', contentIdx + 10);
        if (start < 0) return "";
        start++;

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') break;
            if (c == '\\' && i + 1 < json.length()) {
                i++;
                char next = json.charAt(i);
                if (next == 'n') sb.append('\n');
                else if (next == 't') sb.append('\t');
                else sb.append(next);
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    private static String extractJsonField(String json, String field) {
        String search = "\"" + field + "\":\"";
        int idx = json.indexOf(search);
        if (idx < 0) {
            search = "\"" + field + "\": \"";
            idx = json.indexOf(search);
            if (idx < 0) return null;
        }
        int start = idx + search.length();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') break;
            if (c == '\\' && i + 1 < json.length()) {
                i++;
                sb.append(json.charAt(i));
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
