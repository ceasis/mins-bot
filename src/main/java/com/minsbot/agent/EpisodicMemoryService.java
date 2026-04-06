package com.minsbot.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Unified episodic memory — stores life events/episodes as individual JSON files.
 * Storage: ~/mins_bot_data/episodic_memory/  (one file per episode, named {id}.json)
 */
@Service
public class EpisodicMemoryService {

    private static final Logger log = LoggerFactory.getLogger(EpisodicMemoryService.class);

    private static final Path MEMORY_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "episodic_memory");

    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final ObjectMapper mapper;

    public EpisodicMemoryService() {
        this.mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    @PostConstruct
    void init() {
        try {
            Files.createDirectories(MEMORY_DIR);
            long count;
            try (Stream<Path> files = Files.list(MEMORY_DIR)) {
                count = files.filter(p -> p.toString().endsWith(".json")).count();
            }
            log.info("[EpisodicMemory] Ready — {} episodes in {}", count, MEMORY_DIR);
        } catch (IOException e) {
            log.error("[EpisodicMemory] Failed to create memory directory: {}", e.getMessage());
        }
    }

    // ═══ Core operations ═══

    /**
     * Save a new episode and return its ID.
     */
    public String saveEpisode(String type, String summary, String details,
                              List<String> tags, List<String> people, int importance) {
        String id = "ep-" + System.currentTimeMillis();

        Map<String, Object> episode = new LinkedHashMap<>();
        episode.put("id", id);
        episode.put("timestamp", LocalDateTime.now().format(TIMESTAMP_FMT));
        episode.put("type", type != null ? type : "custom");
        episode.put("summary", summary);
        episode.put("details", details);
        episode.put("tags", tags != null ? tags : List.of());
        episode.put("people", people != null ? people : List.of());
        episode.put("location", null);
        episode.put("platform", "desktop");
        episode.put("mood", null);
        episode.put("importance", Math.max(1, Math.min(5, importance)));
        episode.put("relatedEpisodes", List.of());

        try {
            Path file = MEMORY_DIR.resolve(id + ".json");
            mapper.writeValue(file.toFile(), episode);
            log.debug("[EpisodicMemory] Saved episode {} — {}", id, summary);
            return id;
        } catch (IOException e) {
            log.error("[EpisodicMemory] Failed to save episode: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Text search across summaries, details, and tags (case-insensitive substring).
     */
    public List<Map<String, Object>> searchEpisodes(String query, int limit) {
        if (query == null || query.isBlank()) return List.of();
        String lower = query.toLowerCase();
        int effectiveLimit = limit > 0 ? limit : 20;

        return loadAllEpisodes().stream()
                .filter(ep -> matchesQuery(ep, lower))
                .sorted(byTimestampDesc())
                .limit(effectiveLimit)
                .collect(Collectors.toList());
    }

    /**
     * Filter episodes by type.
     */
    public List<Map<String, Object>> getEpisodesByType(String type, int limit) {
        int effectiveLimit = limit > 0 ? limit : 20;
        return loadAllEpisodes().stream()
                .filter(ep -> type.equalsIgnoreCase(getString(ep, "type")))
                .sorted(byTimestampDesc())
                .limit(effectiveLimit)
                .collect(Collectors.toList());
    }

    /**
     * Filter episodes by date range (inclusive).
     */
    public List<Map<String, Object>> getEpisodesByDateRange(LocalDate startDate, LocalDate endDate) {
        return loadAllEpisodes().stream()
                .filter(ep -> {
                    LocalDate date = parseDate(getString(ep, "timestamp"));
                    if (date == null) return false;
                    return !date.isBefore(startDate) && !date.isAfter(endDate);
                })
                .sorted(byTimestampDesc())
                .collect(Collectors.toList());
    }

    /**
     * Find episodes involving a specific person (case-insensitive).
     */
    public List<Map<String, Object>> getEpisodesByPerson(String personName) {
        if (personName == null || personName.isBlank()) return List.of();
        String lower = personName.toLowerCase();

        return loadAllEpisodes().stream()
                .filter(ep -> {
                    List<String> people = getStringList(ep, "people");
                    return people.stream().anyMatch(p -> p.toLowerCase().contains(lower));
                })
                .sorted(byTimestampDesc())
                .collect(Collectors.toList());
    }

    /**
     * Filter episodes by tag (case-insensitive).
     */
    public List<Map<String, Object>> getEpisodesByTag(String tag) {
        if (tag == null || tag.isBlank()) return List.of();
        String lower = tag.toLowerCase();

        return loadAllEpisodes().stream()
                .filter(ep -> {
                    List<String> tags = getStringList(ep, "tags");
                    return tags.stream().anyMatch(t -> t.toLowerCase().equals(lower));
                })
                .sorted(byTimestampDesc())
                .collect(Collectors.toList());
    }

    /**
     * Get the N most recent episodes.
     */
    public List<Map<String, Object>> getRecentEpisodes(int limit) {
        int effectiveLimit = limit > 0 ? limit : 20;
        return loadAllEpisodes().stream()
                .sorted(byTimestampDesc())
                .limit(effectiveLimit)
                .collect(Collectors.toList());
    }

    /**
     * Get a single episode by ID.
     */
    public Map<String, Object> getEpisode(String id) {
        if (id == null || id.isBlank()) return null;
        Path file = MEMORY_DIR.resolve(id + ".json");
        if (!Files.exists(file)) return null;
        return loadEpisode(file);
    }

    /**
     * Delete a single episode.
     */
    public boolean deleteEpisode(String id) {
        if (id == null || id.isBlank()) return false;
        try {
            Path file = MEMORY_DIR.resolve(id + ".json");
            if (Files.deleteIfExists(file)) {
                log.debug("[EpisodicMemory] Deleted episode {}", id);
                return true;
            }
            return false;
        } catch (IOException e) {
            log.error("[EpisodicMemory] Failed to delete episode {}: {}", id, e.getMessage());
            return false;
        }
    }

    /**
     * Return summary stats: total episodes, count by type, date range.
     */
    public Map<String, Object> getMemorySummary() {
        List<Map<String, Object>> all = loadAllEpisodes();

        Map<String, Long> byType = all.stream()
                .collect(Collectors.groupingBy(
                        ep -> getString(ep, "type") != null ? getString(ep, "type") : "unknown",
                        Collectors.counting()));

        String earliest = all.stream()
                .map(ep -> getString(ep, "timestamp"))
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);

        String latest = all.stream()
                .map(ep -> getString(ep, "timestamp"))
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalEpisodes", all.size());
        summary.put("byType", byType);
        summary.put("earliestTimestamp", earliest);
        summary.put("latestTimestamp", latest);
        return summary;
    }

    /**
     * Remove old low-importance episodes (importance <= 2) older than the given number of days.
     */
    public int pruneOldEpisodes(int daysToKeep) {
        LocalDate cutoff = LocalDate.now().minusDays(daysToKeep);
        int deleted = 0;

        for (Map<String, Object> ep : loadAllEpisodes()) {
            LocalDate date = parseDate(getString(ep, "timestamp"));
            if (date == null) continue;

            int importance = getInt(ep, "importance", 3);
            if (date.isBefore(cutoff) && importance <= 2) {
                String id = getString(ep, "id");
                if (id != null && deleteEpisode(id)) {
                    deleted++;
                }
            }
        }

        log.info("[EpisodicMemory] Pruned {} low-importance episodes older than {} days", deleted, daysToKeep);
        return deleted;
    }

    // ═══ Internal helpers ═══

    private List<Map<String, Object>> loadAllEpisodes() {
        List<Map<String, Object>> episodes = new ArrayList<>();
        try (Stream<Path> files = Files.list(MEMORY_DIR)) {
            files.filter(p -> p.toString().endsWith(".json"))
                    .forEach(p -> {
                        Map<String, Object> ep = loadEpisode(p);
                        if (ep != null) episodes.add(ep);
                    });
        } catch (IOException e) {
            log.error("[EpisodicMemory] Failed to list episodes: {}", e.getMessage());
        }
        return episodes;
    }

    private Map<String, Object> loadEpisode(Path file) {
        try {
            return mapper.readValue(file.toFile(), new TypeReference<>() {});
        } catch (IOException e) {
            log.warn("[EpisodicMemory] Failed to read {}: {}", file.getFileName(), e.getMessage());
            return null;
        }
    }

    private boolean matchesQuery(Map<String, Object> ep, String lowerQuery) {
        String summary = getString(ep, "summary");
        if (summary != null && summary.toLowerCase().contains(lowerQuery)) return true;

        String details = getString(ep, "details");
        if (details != null && details.toLowerCase().contains(lowerQuery)) return true;

        List<String> tags = getStringList(ep, "tags");
        for (String tag : tags) {
            if (tag.toLowerCase().contains(lowerQuery)) return true;
        }

        return false;
    }

    private Comparator<Map<String, Object>> byTimestampDesc() {
        return (a, b) -> {
            String tsA = getString(a, "timestamp");
            String tsB = getString(b, "timestamp");
            if (tsA == null && tsB == null) return 0;
            if (tsA == null) return 1;
            if (tsB == null) return -1;
            return tsB.compareTo(tsA);
        };
    }

    private LocalDate parseDate(String timestamp) {
        if (timestamp == null) return null;
        try {
            return LocalDateTime.parse(timestamp, TIMESTAMP_FMT).toLocalDate();
        } catch (Exception e) {
            return null;
        }
    }

    private static String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val instanceof String s ? s : null;
    }

    private static int getInt(Map<String, Object> map, String key, int defaultVal) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        return defaultVal;
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
