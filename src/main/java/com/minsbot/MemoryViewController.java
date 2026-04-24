package com.minsbot;

import com.minsbot.agent.AutoMemoryExtractor;
import com.minsbot.agent.EpisodicMemoryService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * UI surface for the Auto-Memory / Episodic Memory system. Lets the user see what
 * the bot has auto-remembered, search/filter it, delete entries, and pause the
 * extractor if they want privacy.
 */
@RestController
@RequestMapping("/api/episodic-memory")
public class MemoryViewController {

    private final EpisodicMemoryService memory;
    private final AutoMemoryExtractor extractor;

    public MemoryViewController(EpisodicMemoryService memory, AutoMemoryExtractor extractor) {
        this.memory = memory;
        this.extractor = extractor;
    }

    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> status() {
        return Map.of(
                "autoMemoryEnabled", extractor.isEnabled(),
                "summary", memory.getMemorySummary()
        );
    }

    @PostMapping(value = "/auto-enabled", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> setAutoEnabled(@RequestBody Map<String, Object> body) {
        Object val = body == null ? null : body.get("enabled");
        if (val != null) extractor.setEnabled(Boolean.parseBoolean(val.toString()));
        return Map.of("enabled", extractor.isEnabled());
    }

    /** Recent episodes (newest first). Default limit 100. */
    @GetMapping(value = "/recent", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> recent(@RequestParam(defaultValue = "100") int limit) {
        return memory.getRecentEpisodes(limit);
    }

    /** Free-text search over summary/details/tags. */
    @GetMapping(value = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> search(@RequestParam String q,
                                             @RequestParam(defaultValue = "100") int limit) {
        return memory.searchEpisodes(q, limit);
    }

    @DeleteMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> delete(@PathVariable String id) {
        return Map.of("deleted", memory.deleteEpisode(id));
    }
}
