package com.minsbot.skills.metaanalyzer;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/metaanalyzer")
public class MetaAnalyzerController {

    private final MetaAnalyzerService service;
    private final MetaAnalyzerConfig.MetaAnalyzerProperties properties;

    public MetaAnalyzerController(MetaAnalyzerService service, MetaAnalyzerConfig.MetaAnalyzerProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "metaanalyzer",
                "enabled", properties.isEnabled(),
                "timeoutMs", properties.getTimeoutMs(),
                "maxBytes", properties.getMaxBytes()
        ));
    }

    @GetMapping("/analyze")
    public ResponseEntity<?> analyze(@RequestParam String url) {
        if (!properties.isEnabled()) return disabled();
        try {
            return ResponseEntity.ok(service.analyze(url));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<Map<String, Object>> disabled() {
        return ResponseEntity.status(403).body(Map.of("error", "MetaAnalyzer skill is disabled"));
    }
}
