package com.minsbot.skills.langdetector;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/langdetector")
public class LangDetectorController {
    private final LangDetectorService service;
    private final LangDetectorConfig.LangDetectorProperties properties;

    public LangDetectorController(LangDetectorService service, LangDetectorConfig.LangDetectorProperties properties) {
        this.service = service; this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of("skill", "langdetector", "enabled", properties.isEnabled(),
                "maxTextChars", properties.getMaxTextChars()));
    }

    @PostMapping("/detect")
    public ResponseEntity<?> detect(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "LangDetector skill is disabled"));
        String text = body.get("text");
        if (text == null) return ResponseEntity.badRequest().body(Map.of("error", "text required"));
        if (text.length() > properties.getMaxTextChars()) return ResponseEntity.badRequest().body(Map.of("error", "text exceeds maxTextChars"));
        return ResponseEntity.ok(service.detect(text));
    }
}
