package com.minsbot.skills.headlineanalyzer;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/headlineanalyzer")
public class HeadlineAnalyzerController {
    private final HeadlineAnalyzerService service;
    private final HeadlineAnalyzerConfig.HeadlineAnalyzerProperties properties;
    public HeadlineAnalyzerController(HeadlineAnalyzerService service, HeadlineAnalyzerConfig.HeadlineAnalyzerProperties properties) { this.service = service; this.properties = properties; }

    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "headlineanalyzer", "enabled", properties.isEnabled())); }

    @PostMapping("/analyze") public ResponseEntity<?> analyze(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "HeadlineAnalyzer skill is disabled"));
        return ResponseEntity.ok(service.analyze(body.get("headline")));
    }
}
