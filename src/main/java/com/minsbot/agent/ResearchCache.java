package com.minsbot.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * File-backed cache for {@link com.minsbot.agent.tools.ResearchTool} results,
 * keyed by normalized query text. Entries expire after a TTL (default 24h);
 * past that, the cached value is ignored and the underlying fetcher runs again.
 *
 * <p>This turns the deliverable executor from amnesic ("research vector DBs"
 * always pays for 4 web fetches + 1 synthesis) into incremental ("we already
 * researched this 3 hours ago, reuse it; it'd take a separate cache-bust to
 * pay for that work again").
 *
 * <p>Cache files live in {@code ~/mins_bot_data/research_cache/} as JSON, named
 * by SHA-256 of the normalized query. Manual invalidation = delete the folder
 * (or call {@link #invalidate(String)}). Memory-only mirror in
 * {@link #memTable} avoids re-reading disk for repeated hits within a session.
 */
@Service
public class ResearchCache {

    private static final Logger log = LoggerFactory.getLogger(ResearchCache.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private final Path dir = Paths.get(
            System.getProperty("user.home"), "mins_bot_data", "research_cache");
    private final ConcurrentHashMap<String, Entry> memTable = new ConcurrentHashMap<>();

    public ResearchCache() {
        try { Files.createDirectories(dir); }
        catch (Exception e) { log.warn("[ResearchCache] cannot create dir {}: {}", dir, e.getMessage()); }
    }

    public String getOrCompute(String query, Supplier<String> fetcher) {
        return getOrCompute(query, DEFAULT_TTL, fetcher);
    }

    public String getOrCompute(String query, Duration ttl, Supplier<String> fetcher) {
        if (query == null || query.isBlank()) return fetcher.get();
        String key = key(query);
        Entry hit = lookup(key, ttl);
        if (hit != null) {
            log.info("[ResearchCache] HIT {} (age {}s)", truncate(query, 60),
                    Duration.between(hit.timestamp, Instant.now()).toSeconds());
            return hit.value;
        }
        log.info("[ResearchCache] MISS {}", truncate(query, 60));
        String fresh = fetcher.get();
        if (fresh != null && !fresh.isBlank() && !looksFailed(fresh)) {
            store(key, query, fresh);
        }
        return fresh;
    }

    public void invalidate(String query) {
        if (query == null) return;
        String key = key(query);
        memTable.remove(key);
        try { Files.deleteIfExists(dir.resolve(key + ".json")); }
        catch (Exception ignored) {}
    }

    public int size() {
        try (var stream = Files.list(dir)) {
            return (int) stream.filter(p -> p.toString().endsWith(".json")).count();
        } catch (Exception e) {
            return memTable.size();
        }
    }

    // ─── Internals ───────────────────────────────────────────────────────

    private Entry lookup(String key, Duration ttl) {
        Entry e = memTable.get(key);
        if (e == null) {
            e = readFromDisk(key);
            if (e != null) memTable.put(key, e);
        }
        if (e == null) return null;
        if (Duration.between(e.timestamp, Instant.now()).compareTo(ttl) > 0) {
            // Stale — drop it so a refresh writes the new value
            memTable.remove(key);
            try { Files.deleteIfExists(dir.resolve(key + ".json")); } catch (Exception ignored) {}
            return null;
        }
        return e;
    }

    private Entry readFromDisk(String key) {
        Path file = dir.resolve(key + ".json");
        if (!Files.isRegularFile(file)) return null;
        try {
            JsonNode root = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            String value = root.path("value").asText("");
            String tsStr = root.path("timestamp").asText("");
            if (value.isEmpty() || tsStr.isEmpty()) return null;
            return new Entry(value, Instant.parse(tsStr));
        } catch (Exception e) {
            log.debug("[ResearchCache] read failed {}: {}", file, e.getMessage());
            return null;
        }
    }

    private void store(String key, String query, String value) {
        Entry e = new Entry(value, Instant.now());
        memTable.put(key, e);
        try {
            String json = JSON.writeValueAsString(java.util.Map.of(
                    "query", query,
                    "value", value,
                    "timestamp", e.timestamp.toString()));
            Files.writeString(dir.resolve(key + ".json"), json, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            log.debug("[ResearchCache] write failed for {}: {}", truncate(query, 60), ex.getMessage());
        }
    }

    /** Normalized SHA-256 hash of the query — collapses whitespace, lowercases. */
    private static String key(String query) {
        String norm = query.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(norm.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            // Use first 8 bytes (16 hex chars) — collision risk negligible at this volume,
            // and short filenames are friendlier on Windows.
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", hash[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(norm.hashCode());
        }
    }

    /** Same heuristic as DeliverableExecutor — don't cache obvious failures. */
    private static boolean looksFailed(String result) {
        if (result == null || result.length() < 60) return true;
        String low = result.toLowerCase();
        return low.startsWith("(step failed")
            || low.contains("no search results")
            || low.contains("search succeeded but no urls")
            || low.contains("(fetch failed");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private record Entry(String value, Instant timestamp) {}
}
