package com.minsbot.skills.loganalyzer;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/loganalyzer")
public class LogAnalyzerController {
    private final LogAnalyzerService service;
    private final LogAnalyzerConfig.LogAnalyzerProperties properties;
    public LogAnalyzerController(LogAnalyzerService service, LogAnalyzerConfig.LogAnalyzerProperties properties) { this.service = service; this.properties = properties; }

    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "loganalyzer", "enabled", properties.isEnabled(), "maxLines", properties.getMaxLines())); }

    @PostMapping("/analyze") public ResponseEntity<?> analyze(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "LogAnalyzer skill is disabled"));
        String log = body.get("log"); if (log == null) return ResponseEntity.badRequest().body(Map.of("error", "log required"));
        return ResponseEntity.ok(service.analyze(log));
    }
}
