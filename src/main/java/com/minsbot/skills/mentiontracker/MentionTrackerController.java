package com.minsbot.skills.mentiontracker;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/mentiontracker")
public class MentionTrackerController {

    private final MentionTrackerService service;
    private final MentionTrackerConfig.MentionTrackerProperties props;

    public MentionTrackerController(MentionTrackerService service,
                                    MentionTrackerConfig.MentionTrackerProperties props) {
        this.service = service;
        this.props = props;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of("skill", "mentiontracker", "enabled", props.isEnabled(),
                "storageDir", props.getStorageDir(),
                "purpose", "Poll search RSS feeds for product mentions, dedupe, classify sentiment"));
    }

    @PostMapping("/poll")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> poll(@RequestBody Map<String, Object> body) {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "mentiontracker skill is disabled"));
        try {
            List<String> kw = (List<String>) body.getOrDefault("brandKeywords", List.of());
            List<String> sources = (List<String>) body.getOrDefault("sources", List.of());
            return ResponseEntity.ok(service.poll(kw, sources));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/recent")
    public ResponseEntity<?> recent(@RequestParam(defaultValue = "50") int limit) {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "mentiontracker skill is disabled"));
        try { return ResponseEntity.ok(Map.of("mentions", service.recent(limit))); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
