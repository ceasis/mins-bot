package com.minsbot.skills.competitor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/competitor")
public class CompetitorController {

    private final CompetitorService service;
    private final CompetitorConfig.CompetitorProperties props;

    public CompetitorController(CompetitorService service, CompetitorConfig.CompetitorProperties props) {
        this.service = service;
        this.props = props;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "competitor",
                "enabled", props.isEnabled(),
                "maxSites", props.getMaxSites(),
                "purpose", "Analyze competitor sites for positioning, CTAs, vocabulary, and key pages"
        ));
    }

    @PostMapping("/run")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> run(@RequestBody Map<String, Object> body) {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "competitor skill is disabled"));
        try {
            List<String> sites = (List<String>) body.getOrDefault("sites", List.of());
            return ResponseEntity.ok(service.analyze(sites));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
