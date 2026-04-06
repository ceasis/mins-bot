package com.minsbot.agent.tools;

import com.minsbot.agent.EpisodicMemoryService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI-callable tools for the unified episodic memory system.
 * Use these to remember and recall life events, conversations, observations, and experiences.
 */
@Component
public class EpisodicMemoryTools {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ToolExecutionNotifier notifier;
    private final EpisodicMemoryService episodicMemory;

    public EpisodicMemoryTools(ToolExecutionNotifier notifier, EpisodicMemoryService episodicMemory) {
        this.notifier = notifier;
        this.episodicMemory = episodicMemory;
    }

    @Tool(description = "Remember/store a life event or episode. Use when the user shares something worth remembering: "
            + "conversations, observations, health updates, financial events, relationship moments, work events, etc. "
            + "Type must be one of: conversation, observation, health, finance, relationship, work, reminder, custom. "
            + "Tags is a comma-separated list of keywords. People is a comma-separated list of names involved. "
            + "Importance is 1-5 (1=trivial, 5=life-changing).")
    public String rememberEvent(
            @ToolParam(description = "Episode type: conversation, observation, health, finance, relationship, work, reminder, or custom") String type,
            @ToolParam(description = "Brief one-line summary of the event") String summary,
            @ToolParam(description = "Full details of the event") String details,
            @ToolParam(description = "Comma-separated tags/keywords, e.g. 'work,deadline,project-x'") String tags,
            @ToolParam(description = "Comma-separated names of people involved, e.g. 'John,Maria'") String people,
            @ToolParam(description = "Importance 1-5 (1=trivial, 5=life-changing)") int importance) {
        notifier.notify("Remembering event...");

        List<String> tagList = parseCommaSeparated(tags);
        List<String> peopleList = parseCommaSeparated(people);

        String id = episodicMemory.saveEpisode(type, summary, details, tagList, peopleList, importance);
        if (id == null) {
            return "Failed to save memory.";
        }
        return "Remembered: " + summary + " (id: " + id + ", importance: " + importance + ")";
    }

    @Tool(description = "Search/recall memories by a text query. Searches across summaries, details, and tags. "
            + "Use when the user asks 'do you remember...', 'what do you know about...', 'recall anything about...'.")
    public String recallMemories(
            @ToolParam(description = "Search query text") String query) {
        notifier.notify("Searching memories: " + query);

        List<Map<String, Object>> results = episodicMemory.searchEpisodes(query, 20);
        if (results.isEmpty()) {
            return "No memories found matching '" + query + "'.";
        }
        return formatEpisodes(results, "Memories matching '" + query + "'");
    }

    @Tool(description = "Recall memories involving a specific person. "
            + "Use when the user asks 'what do you remember about John?', 'any interactions with Maria?'.")
    public String recallByPerson(
            @ToolParam(description = "Person's name to search for") String personName) {
        notifier.notify("Recalling memories about " + personName + "...");

        List<Map<String, Object>> results = episodicMemory.getEpisodesByPerson(personName);
        if (results.isEmpty()) {
            return "No memories found involving '" + personName + "'.";
        }
        return formatEpisodes(results, "Memories involving '" + personName + "'");
    }

    @Tool(description = "Recall memories by topic/tag. "
            + "Use when the user asks 'what do you remember about work?', 'anything about health?'.")
    public String recallByTopic(
            @ToolParam(description = "Tag/topic to search for, e.g. 'work', 'health', 'project-x'") String tag) {
        notifier.notify("Recalling memories about " + tag + "...");

        List<Map<String, Object>> results = episodicMemory.getEpisodesByTag(tag);
        if (results.isEmpty()) {
            return "No memories found with tag '" + tag + "'.";
        }
        return formatEpisodes(results, "Memories tagged '" + tag + "'");
    }

    @Tool(description = "Recall the most recent memories. "
            + "Use when the user asks 'what happened recently?', 'what do you remember lately?'.")
    public String recallRecent(
            @ToolParam(description = "Number of recent memories to return (default 10)") int count) {
        int effective = count > 0 ? count : 10;
        notifier.notify("Recalling " + effective + " recent memories...");

        List<Map<String, Object>> results = episodicMemory.getRecentEpisodes(effective);
        if (results.isEmpty()) {
            return "No memories stored yet.";
        }
        return formatEpisodes(results, "Recent memories");
    }

    @Tool(description = "Recall memories from a specific date range. "
            + "Use when the user asks 'what happened last week?', 'what do you remember from January?'. "
            + "Dates in YYYY-MM-DD format.")
    public String recallByDate(
            @ToolParam(description = "Start date in YYYY-MM-DD format") String startDate,
            @ToolParam(description = "End date in YYYY-MM-DD format") String endDate) {
        notifier.notify("Recalling memories from " + startDate + " to " + endDate + "...");

        try {
            LocalDate start = LocalDate.parse(startDate, DATE_FMT);
            LocalDate end = LocalDate.parse(endDate, DATE_FMT);

            List<Map<String, Object>> results = episodicMemory.getEpisodesByDateRange(start, end);
            if (results.isEmpty()) {
                return "No memories found between " + startDate + " and " + endDate + ".";
            }
            return formatEpisodes(results, "Memories from " + startDate + " to " + endDate);
        } catch (Exception e) {
            return "Invalid date format. Use YYYY-MM-DD, e.g. '2026-01-15'.";
        }
    }

    @Tool(description = "Delete/forget a specific memory by its episode ID. "
            + "Use when the user asks to forget or remove a specific memory.")
    public String forgetMemory(
            @ToolParam(description = "Episode ID to delete, e.g. 'ep-1712345678901'") String episodeId) {
        notifier.notify("Forgetting memory " + episodeId + "...");

        boolean deleted = episodicMemory.deleteEpisode(episodeId);
        if (deleted) {
            return "Memory " + episodeId + " has been forgotten.";
        }
        return "Memory " + episodeId + " not found.";
    }

    @Tool(description = "Get statistics about stored memories: total count, breakdown by type, date range. "
            + "Use when the user asks 'how many memories?', 'memory stats', 'how much do you remember?'.")
    public String getMemoryStats() {
        notifier.notify("Getting memory stats...");

        Map<String, Object> summary = episodicMemory.getMemorySummary();
        int total = (int) summary.get("totalEpisodes");
        if (total == 0) {
            return "No memories stored yet.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Memory Statistics:\n");
        sb.append("  Total episodes: ").append(total).append("\n");

        @SuppressWarnings("unchecked")
        Map<String, Long> byType = (Map<String, Long>) summary.get("byType");
        if (byType != null && !byType.isEmpty()) {
            sb.append("  By type:\n");
            byType.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .forEach(e -> sb.append("    ").append(e.getKey()).append(": ").append(e.getValue()).append("\n"));
        }

        String earliest = (String) summary.get("earliestTimestamp");
        String latest = (String) summary.get("latestTimestamp");
        if (earliest != null) sb.append("  Earliest: ").append(earliest).append("\n");
        if (latest != null) sb.append("  Latest: ").append(latest).append("\n");

        return sb.toString().trim();
    }

    // ═══ Helpers ═══

    private List<String> parseCommaSeparated(String input) {
        if (input == null || input.isBlank()) return List.of();
        return Arrays.stream(input.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private String formatEpisodes(List<Map<String, Object>> episodes, String header) {
        StringBuilder sb = new StringBuilder();
        sb.append(header).append(" (").append(episodes.size()).append(" found):\n\n");

        for (Map<String, Object> ep : episodes) {
            sb.append("[").append(getString(ep, "timestamp", "?")).append("] ");
            sb.append("(").append(getString(ep, "type", "?")).append(") ");
            sb.append(getString(ep, "summary", "")).append("\n");

            String details = getString(ep, "details", "");
            if (!details.isEmpty()) {
                // Truncate long details for display
                String truncated = details.length() > 200 ? details.substring(0, 200) + "..." : details;
                sb.append("  Details: ").append(truncated).append("\n");
            }

            String id = getString(ep, "id", "");
            List<String> tags = getStringList(ep, "tags");
            List<String> people = getStringList(ep, "people");

            if (!tags.isEmpty()) sb.append("  Tags: ").append(String.join(", ", tags)).append("\n");
            if (!people.isEmpty()) sb.append("  People: ").append(String.join(", ", people)).append("\n");
            sb.append("  ID: ").append(id).append(" | Importance: ").append(ep.getOrDefault("importance", "?")).append("\n\n");
        }

        return sb.toString().trim();
    }

    private static String getString(Map<String, Object> map, String key, String defaultVal) {
        Object val = map.get(key);
        return val instanceof String s ? s : defaultVal;
    }

    @SuppressWarnings("unchecked")
    private static List<String> getStringList(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
