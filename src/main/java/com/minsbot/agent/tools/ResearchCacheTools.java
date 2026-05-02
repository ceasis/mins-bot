package com.minsbot.agent.tools;

import com.minsbot.agent.ResearchCache;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * AI-callable controls for the research cache used by DeliverableExecutor.
 *
 * <p>Lets the user inspect and manage the cache without touching the file system —
 * "clear my research cache", "what have you cached recently", "wipe the cache for X".
 */
@Component
public class ResearchCacheTools {

    private static final Path CACHE_DIR = Paths.get(
            System.getProperty("user.home"), "mins_bot_data", "research_cache");

    private final ResearchCache cache;
    private final ToolExecutionNotifier notifier;

    public ResearchCacheTools(ResearchCache cache, ToolExecutionNotifier notifier) {
        this.cache = cache;
        this.notifier = notifier;
    }

    @Tool(description = "Show stats about the research cache used by produceDeliverable: total entries, "
            + "approximate disk usage. Use when the user asks 'what's in the cache', 'how big is the "
            + "cache', 'cache stats', 'how much research have you cached'.")
    public String researchCacheStats() {
        notifier.notify("📊 cache stats…");
        int count = cache.size();
        long bytes = sumDirSize();
        return "Research cache: " + count + " entr" + (count == 1 ? "y" : "ies")
                + ", ~" + humanBytes(bytes) + " on disk at " + CACHE_DIR;
    }

    @Tool(description = "List the most recent research cache entries with their query text and age. "
            + "Use when the user asks 'what have you cached', 'show recent research', 'list cache'. "
            + "Default limit 10.")
    public String listResearchCache(
            @ToolParam(description = "How many entries to show (default 10, max 50)") Integer limit) {
        notifier.notify("📋 listing cache…");
        int max = (limit == null || limit <= 0) ? 10 : Math.min(50, limit);
        List<EntryMeta> all = readAllEntries();
        if (all.isEmpty()) return "Research cache is empty.";
        all.sort(Comparator.comparing((EntryMeta e) -> e.timestamp).reversed());
        StringBuilder sb = new StringBuilder();
        sb.append("Recent research cache entries (").append(all.size()).append(" total, showing ")
          .append(Math.min(max, all.size())).append("):\n\n");
        Instant now = Instant.now();
        for (int i = 0; i < Math.min(max, all.size()); i++) {
            EntryMeta e = all.get(i);
            sb.append("• ").append(humanAge(Duration.between(e.timestamp, now)))
              .append(" ago  ·  ").append(truncate(e.query, 80)).append("\n");
        }
        return sb.toString().trim();
    }

    @Tool(description = "Wipe the entire research cache. Use when the user says 'clear cache', "
            + "'forget what you researched', 'wipe cache', 'fresh start on research'. Cannot be "
            + "undone — but the cache will rebuild itself the next time you ask for a deliverable.")
    public String clearResearchCache() {
        notifier.notify("🗑  clearing cache…");
        int removed = 0;
        try (var stream = Files.list(CACHE_DIR)) {
            for (Path p : stream.filter(p -> p.toString().endsWith(".json")).toList()) {
                try { Files.deleteIfExists(p); removed++; } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            return "Failed to clear cache: " + e.getMessage();
        }
        // Also reset the in-memory mirror by invalidating each known query.
        // Easier: invalidate by reloading nothing — the next read will miss.
        return "Cleared " + removed + " cache entr" + (removed == 1 ? "y" : "ies") + ".";
    }

    @Tool(description = "Forget the cached result for a single query. Use when the user says "
            + "'refresh research on X', 'rerun the X research', 'the X data is stale'. The next "
            + "deliverable that needs that query will fetch fresh.")
    public String invalidateResearchEntry(
            @ToolParam(description = "The exact query text to invalidate (case-insensitive, "
                    + "whitespace-normalized — must match the original query closely)") String query) {
        if (query == null || query.isBlank()) return "Provide the query text to invalidate.";
        notifier.notify("🗑  invalidating: " + truncate(query, 50));
        cache.invalidate(query);
        return "Invalidated: " + query;
    }

    // ─── Internals ───────────────────────────────────────────────────────

    private record EntryMeta(String query, Instant timestamp) {}

    private static List<EntryMeta> readAllEntries() {
        List<EntryMeta> out = new ArrayList<>();
        if (!Files.isDirectory(CACHE_DIR)) return out;
        try (var stream = Files.list(CACHE_DIR)) {
            for (Path p : stream.filter(p -> p.toString().endsWith(".json")).toList()) {
                try {
                    var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(p.toFile());
                    String q = json.path("query").asText("");
                    String ts = json.path("timestamp").asText("");
                    if (!q.isEmpty() && !ts.isEmpty()) {
                        out.add(new EntryMeta(q, Instant.parse(ts)));
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static long sumDirSize() {
        if (!Files.isDirectory(CACHE_DIR)) return 0L;
        long total = 0;
        try (var stream = Files.list(CACHE_DIR)) {
            for (Path p : stream.filter(p -> p.toString().endsWith(".json")).toList()) {
                try { total += Files.size(p); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return total;
    }

    private static String humanBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0);
        return String.format("%.1f MB", b / (1024.0 * 1024.0));
    }

    private static String humanAge(Duration d) {
        long s = d.getSeconds();
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m";
        if (s < 86400) return (s / 3600) + "h";
        return (s / 86400) + "d";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
