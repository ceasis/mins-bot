package com.minsbot.skills.marketresearch;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/marketresearch")
public class MarketResearchController {

    private final MarketResearchService service;
    private final MarketResearchConfig.MarketResearchProperties props;

    public MarketResearchController(MarketResearchService service,
                                    MarketResearchConfig.MarketResearchProperties props) {
        this.service = service;
        this.props = props;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "marketresearch",
                "enabled", props.isEnabled(),
                "maxSources", props.getMaxSources(),
                "maxResults", props.getMaxResults(),
                "purpose", "Aggregate market news with ticker + sentiment signals (research only)"
        ));
    }

    @PostMapping("/run")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> run(@RequestBody Map<String, Object> body) {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "marketresearch skill is disabled"));
        try {
            List<String> tickers = (List<String>) body.getOrDefault("tickers", List.of());
            List<String> sources = (List<String>) body.getOrDefault("sources", List.of());
            Integer max = body.get("maxResults") instanceof Number n ? n.intValue() : null;
            return ResponseEntity.ok(service.run(tickers, sources, max));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
