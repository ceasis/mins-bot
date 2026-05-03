package com.minsbot.skills.arbiscout;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/arbiscout")
public class ArbiScoutController {

    private final ArbiScoutService service;
    private final ArbiScoutConfig.ArbiScoutProperties props;

    public ArbiScoutController(ArbiScoutService service, ArbiScoutConfig.ArbiScoutProperties props) {
        this.service = service;
        this.props = props;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "arbiscout",
                "enabled", props.isEnabled(),
                "maxSources", props.getMaxSources(),
                "maxResults", props.getMaxResults(),
                "purpose", "Scan marketplace feeds for underpriced/arbitrage opportunities"
        ));
    }

    @PostMapping("/run")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> run(@RequestBody Map<String, Object> body) {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "arbiscout skill is disabled"));
        try {
            List<String> keywords = (List<String>) body.getOrDefault("keywords", List.of());
            List<String> sources = (List<String>) body.getOrDefault("sources", List.of());
            Double maxPrice = body.get("maxPrice") instanceof Number n ? n.doubleValue() : null;
            Double minPrice = body.get("minPrice") instanceof Number n ? n.doubleValue() : null;
            Integer max = body.get("maxResults") instanceof Number n ? n.intValue() : null;
            return ResponseEntity.ok(service.run(keywords, sources, maxPrice, minPrice, max));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
