package com.minsbot.skills.gighunter;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/gighunter")
public class GigHunterController {

    private final GigHunterService service;
    private final GigHunterConfig.GigHunterProperties props;

    public GigHunterController(GigHunterService service, GigHunterConfig.GigHunterProperties props) {
        this.service = service;
        this.props = props;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "gighunter",
                "enabled", props.isEnabled(),
                "maxSources", props.getMaxSources(),
                "maxResults", props.getMaxResults(),
                "purpose", "Scan freelance/job feeds for matching gigs"
        ));
    }

    @PostMapping("/run")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> run(@RequestBody Map<String, Object> body) {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "gighunter skill is disabled"));
        try {
            List<String> keywords = (List<String>) body.getOrDefault("keywords", List.of());
            List<String> sources = (List<String>) body.getOrDefault("sources", List.of());
            List<String> exclude = (List<String>) body.getOrDefault("excludeKeywords", List.of());
            Integer max = body.get("maxResults") instanceof Number n ? n.intValue() : null;
            return ResponseEntity.ok(service.run(keywords, sources, exclude, max));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
