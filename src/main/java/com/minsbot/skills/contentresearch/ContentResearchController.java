package com.minsbot.skills.contentresearch;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/contentresearch")
public class ContentResearchController {

    private final ContentResearchService service;
    private final ContentResearchConfig.ContentResearchProperties props;

    public ContentResearchController(ContentResearchService service,
                                     ContentResearchConfig.ContentResearchProperties props) {
        this.service = service;
        this.props = props;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "contentresearch",
                "enabled", props.isEnabled(),
                "maxSources", props.getMaxSources(),
                "maxResults", props.getMaxResults(),
                "purpose", "Find trending topics and viral angles for content creation"
        ));
    }

    @PostMapping("/run")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> run(@RequestBody Map<String, Object> body) {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "contentresearch skill is disabled"));
        try {
            List<String> topics = (List<String>) body.getOrDefault("topics", List.of());
            List<String> sources = (List<String>) body.getOrDefault("sources", List.of());
            Integer maxAge = body.get("maxAgeDays") instanceof Number n ? n.intValue() : null;
            Integer max = body.get("maxResults") instanceof Number n ? n.intValue() : null;
            return ResponseEntity.ok(service.run(topics, sources, maxAge, max));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
