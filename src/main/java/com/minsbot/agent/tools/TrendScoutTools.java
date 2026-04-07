package com.minsbot.agent.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Trend Scout: searches YouTube and the web for trending content related to
 * the user's interests. Surfaces new product launches, tech news, tutorials,
 * and updates the user cares about.
 *
 * <p>Example: user uses iPhone + MacBook + AWS → scout finds "iPhone 17 just
 * announced", "new AWS region in Manila", "macOS 16 features you missed".
 *
 * <p>Interests are persisted to ~/mins_bot_data/trend_interests.json.
 * Discovered trends are cached to avoid duplicates.
 */
@Component
public class TrendScoutTools {

    private static final Logger log = LoggerFactory.getLogger(TrendScoutTools.class);
    private static final Path INTERESTS_FILE = Paths.get(
            System.getProperty("user.home"), "mins_bot_data", "trend_interests.json");
    private static final Path CACHE_FILE = Paths.get(
            System.getProperty("user.home"), "mins_bot_data", "trend_cache.json");
    private static final ObjectMapper mapper = new ObjectMapper();

    private final ToolExecutionNotifier notifier;
    private final WebSearchTools webSearchTools;

    private final List<Map<String, Object>> interests = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> trendCache = new CopyOnWriteArrayList<>();
    private static final int MAX_CACHE = 500;

    public TrendScoutTools(ToolExecutionNotifier notifier, WebSearchTools webSearchTools) {
        this.notifier = notifier;
        this.webSearchTools = webSearchTools;
    }

    @PostConstruct
    public void init() {
        loadJson(INTERESTS_FILE, interests);
        loadJson(CACHE_FILE, trendCache);
        // Seed with common tech interests if empty
        if (interests.isEmpty()) {
            log.info("[TrendScout] No interests file found — will learn from conversations.");
        } else {
            log.info("[TrendScout] Loaded {} interests, {} cached trends", interests.size(), trendCache.size());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Interest management
    // ═══════════════════════════════════════════════════════════════════════

    @Tool(description = "Add an interest/topic to track for trending content. "
            + "The bot will search YouTube and the web for news and updates about these topics. "
            + "Use when the user says 'I'm into AWS', 'I use iPhone', 'track React updates', "
            + "'I'm interested in AI news', 'follow Tesla updates'.")
    public String addInterest(
            @ToolParam(description = "Topic to track, e.g. 'iPhone', 'AWS', 'React', 'Tesla', 'mechanical keyboards'") String topic,
            @ToolParam(description = "Category: tech, gaming, finance, health, science, entertainment, sports, other") String category) {
        notifier.notify("Adding interest: " + topic);

        // Check duplicate
        String lower = topic.toLowerCase().trim();
        for (Map<String, Object> i : interests) {
            if (((String) i.get("topic")).toLowerCase().equals(lower)) {
                return "Already tracking: " + topic;
            }
        }

        Map<String, Object> interest = new LinkedHashMap<>();
        interest.put("topic", topic.trim());
        interest.put("category", category != null ? category.trim() : "tech");
        interest.put("addedAt", System.currentTimeMillis());
        interest.put("searchQueries", generateSearchQueries(topic.trim()));
        interests.add(interest);
        saveJson(INTERESTS_FILE, interests);

        return "Now tracking: " + topic + " (" + interest.get("category") + "). "
                + "I'll search YouTube and the web for the latest updates on this topic.";
    }

    @Tool(description = "Remove an interest from tracking.")
    public String removeInterest(
            @ToolParam(description = "Topic to stop tracking") String topic) {
        String lower = topic.toLowerCase().trim();
        boolean removed = interests.removeIf(i -> ((String) i.get("topic")).toLowerCase().contains(lower));
        if (removed) {
            saveJson(INTERESTS_FILE, interests);
            return "Stopped tracking: " + topic;
        }
        return "Not tracking: " + topic;
    }

    @Tool(description = "List all tracked interests/topics.")
    public String listInterests() {
        if (interests.isEmpty()) {
            return "No interests tracked yet. Say 'I'm into X' or 'track X updates' to start. "
                    + "I'll then search YouTube and the web for trending content you care about.";
        }
        StringBuilder sb = new StringBuilder("Tracked Interests (" + interests.size() + "):\n\n");
        for (Map<String, Object> i : interests) {
            sb.append("  • ").append(i.get("topic")).append(" [").append(i.get("category")).append("]\n");
        }
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Trend discovery
    // ═══════════════════════════════════════════════════════════════════════

    @Tool(description = "Search YouTube and the web for trending content about a specific topic. "
            + "Returns latest videos, news, and updates. Use when the user asks 'what's new with iPhone?', "
            + "'any AWS updates?', 'latest React news', 'what's trending in AI?'.")
    public String scoutTopic(
            @ToolParam(description = "Topic to search for trends, e.g. 'iPhone', 'AWS', 'React'") String topic) {
        notifier.notify("Scouting trends for: " + topic);
        StringBuilder results = new StringBuilder();
        results.append("Trend Scout: ").append(topic).append("\n");
        results.append("═".repeat(40)).append("\n\n");

        // YouTube search
        notifier.notify("Searching YouTube for " + topic + "...");
        try {
            String ytQuery = topic + " latest news " + LocalDate.now().getYear();
            String ytResults = webSearchTools.searchWeb("site:youtube.com " + ytQuery);
            if (ytResults != null && !ytResults.isBlank()) {
                results.append("📺 YOUTUBE:\n").append(summarizeResults(ytResults, 5)).append("\n\n");
            }
        } catch (Exception e) {
            results.append("📺 YouTube search failed.\n\n");
        }

        // Web news search
        notifier.notify("Searching web news for " + topic + "...");
        try {
            String newsResults = webSearchTools.searchWeb(topic + " latest news update " + LocalDate.now());
            if (newsResults != null && !newsResults.isBlank()) {
                results.append("📰 NEWS:\n").append(summarizeResults(newsResults, 5)).append("\n\n");
            }
        } catch (Exception e) {
            results.append("📰 News search failed.\n\n");
        }

        // Product/release search
        notifier.notify("Checking for new releases...");
        try {
            String releaseResults = webSearchTools.searchWeb(topic + " new release announcement " + LocalDate.now().getYear());
            if (releaseResults != null && !releaseResults.isBlank()) {
                results.append("🚀 RELEASES:\n").append(summarizeResults(releaseResults, 3)).append("\n\n");
            }
        } catch (Exception e) {
            // skip
        }

        // Cache the results
        cacheTrend(topic, results.toString());

        return results.toString();
    }

    @Tool(description = "Scout ALL tracked interests at once and give a combined trend report. "
            + "Use when the user asks 'what's new?', 'any updates?', 'trend report', "
            + "'what should I know about?', 'scout my interests'.")
    public String scoutAllInterests() {
        if (interests.isEmpty()) {
            return "No interests tracked. Add some first with 'track iPhone updates' or 'I'm into AWS'.";
        }

        notifier.notify("Scouting " + interests.size() + " interests...");
        StringBuilder report = new StringBuilder();
        report.append("Trend Scout Report — ").append(LocalDate.now()).append("\n");
        report.append("═".repeat(50)).append("\n\n");

        for (Map<String, Object> interest : interests) {
            String topic = (String) interest.get("topic");
            notifier.notify("Scouting: " + topic + "...");

            try {
                String query = topic + " latest news " + LocalDate.now().getYear();
                String results = webSearchTools.searchWeb(query);
                if (results != null && !results.isBlank()) {
                    report.append("● ").append(topic.toUpperCase()).append("\n");
                    report.append(summarizeResults(results, 3)).append("\n\n");
                    cacheTrend(topic, results);
                }
            } catch (Exception e) {
                report.append("● ").append(topic).append(": search failed\n\n");
            }
        }

        report.append("Scout complete. ").append(interests.size()).append(" topics checked.");
        return report.toString();
    }

    @Tool(description = "Search YouTube specifically for videos about a topic. "
            + "Returns video titles and links. Use when the user says 'find YouTube videos about X', "
            + "'what's on YouTube about X', 'YouTube updates for X'.")
    public String searchYouTubeTrends(
            @ToolParam(description = "Topic to search YouTube for") String topic) {
        notifier.notify("Searching YouTube: " + topic);
        try {
            String results = webSearchTools.searchWeb("site:youtube.com " + topic + " " + LocalDate.now().getYear());
            if (results == null || results.isBlank()) return "No YouTube results found for: " + topic;

            StringBuilder sb = new StringBuilder("YouTube results for: " + topic + "\n\n");
            sb.append(summarizeResults(results, 8));
            return sb.toString();
        } catch (Exception e) {
            return "YouTube search failed: " + e.getMessage();
        }
    }

    @Tool(description = "Show recently discovered trends from the cache. "
            + "Use when the user asks 'what trends did you find?', 'show cached trends'.")
    public String showRecentTrends(
            @ToolParam(description = "Number of recent trends to show (1-20)") double count) {
        int n = Math.max(1, Math.min(20, (int) count));
        if (trendCache.isEmpty()) return "No cached trends. Run 'scout my interests' first.";

        int start = Math.max(0, trendCache.size() - n);
        StringBuilder sb = new StringBuilder("Recent Trends (" + Math.min(n, trendCache.size()) + "):\n\n");
        for (int i = start; i < trendCache.size(); i++) {
            Map<String, Object> t = trendCache.get(i);
            sb.append("  ").append(t.get("topic")).append(" (").append(t.get("date")).append(")\n");
            String preview = (String) t.getOrDefault("preview", "");
            if (!preview.isBlank()) sb.append("    ").append(preview).append("\n");
            sb.append("\n");
        }
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

    private List<String> generateSearchQueries(String topic) {
        return List.of(
                topic + " latest news",
                topic + " new release " + LocalDate.now().getYear(),
                "site:youtube.com " + topic + " latest"
        );
    }

    private String summarizeResults(String rawResults, int maxItems) {
        // Extract the most useful lines (titles + snippets)
        String[] lines = rawResults.split("\n");
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String line : lines) {
            line = line.trim();
            if (line.isBlank()) continue;
            // Skip lines that are just URLs or metadata
            if (line.startsWith("http") && !line.contains(" ")) continue;
            if (line.length() < 15) continue;

            sb.append("  ").append(line).append("\n");
            count++;
            if (count >= maxItems * 2) break; // title + snippet per item
        }
        return sb.toString().trim();
    }

    private void cacheTrend(String topic, String content) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("topic", topic);
        entry.put("date", LocalDate.now().toString());
        entry.put("preview", content.length() > 200 ? content.substring(0, 200) + "..." : content);
        entry.put("timestamp", System.currentTimeMillis());
        trendCache.add(entry);
        while (trendCache.size() > MAX_CACHE) trendCache.remove(0);
        saveJson(CACHE_FILE, trendCache);
    }

    @SuppressWarnings("unchecked")
    private void loadJson(Path file, List<Map<String, Object>> target) {
        if (Files.exists(file)) {
            try {
                target.addAll(mapper.readValue(file.toFile(), new TypeReference<List<Map<String, Object>>>() {}));
            } catch (IOException e) { log.warn("[TrendScout] Load failed for {}: {}", file.getFileName(), e.getMessage()); }
        }
    }

    private void saveJson(Path file, List<Map<String, Object>> data) {
        try {
            Files.createDirectories(file.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), data);
        } catch (IOException e) { log.error("[TrendScout] Save failed: {}", e.getMessage()); }
    }
}
