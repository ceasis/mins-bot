package com.minsbot.skills.clipboardhistory;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/clipboardhistory")
public class ClipboardHistoryController {

    private final ClipboardHistoryService service;
    private final ClipboardHistoryConfig.ClipboardHistoryProperties properties;

    public ClipboardHistoryController(ClipboardHistoryService service,
                                      ClipboardHistoryConfig.ClipboardHistoryProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "clipboardhistory",
                "enabled", properties.isEnabled(),
                "maxEntries", properties.getMaxEntries(),
                "pollIntervalMs", properties.getPollIntervalMs(),
                "currentSize", service.list().size()
        ));
    }

    @GetMapping
    public ResponseEntity<?> list() {
        if (!properties.isEnabled()) return disabled();
        return ResponseEntity.ok(Map.of("entries", service.list()));
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam String q) {
        if (!properties.isEnabled()) return disabled();
        return ResponseEntity.ok(Map.of("entries", service.search(q)));
    }

    @PostMapping("/{id}/pin")
    public ResponseEntity<?> pin(@PathVariable String id) {
        if (!properties.isEnabled()) return disabled();
        try {
            service.pin(id);
            return ResponseEntity.ok(Map.of("pinned", id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/unpin")
    public ResponseEntity<?> unpin(@PathVariable String id) {
        if (!properties.isEnabled()) return disabled();
        service.unpin(id);
        return ResponseEntity.ok(Map.of("unpinned", id));
    }

    @DeleteMapping
    public ResponseEntity<?> clear() {
        if (!properties.isEnabled()) return disabled();
        service.clear();
        return ResponseEntity.ok(Map.of("cleared", true));
    }

    private ResponseEntity<Map<String, Object>> disabled() {
        return ResponseEntity.status(403).body(Map.of("error", "ClipboardHistory skill is disabled"));
    }
}
