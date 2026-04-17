package com.minsbot.skills.writingtools;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/writingtools")
public class WritingToolsController {
    private final WritingToolsService service;
    private final WritingToolsConfig.WritingToolsProperties properties;

    public WritingToolsController(WritingToolsService service, WritingToolsConfig.WritingToolsProperties properties) {
        this.service = service; this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of("skill", "writingtools", "enabled", properties.isEnabled(),
                "wordsPerMinute", properties.getWordsPerMinute()));
    }

    @PostMapping("/analyze")
    public ResponseEntity<?> analyze(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "WritingTools skill is disabled"));
        String text = body.get("text");
        if (text == null) return ResponseEntity.badRequest().body(Map.of("error", "text required"));
        if (text.length() > properties.getMaxTextChars()) return ResponseEntity.badRequest().body(Map.of("error", "text exceeds maxTextChars"));
        return ResponseEntity.ok(service.analyze(text, properties.getWordsPerMinute()));
    }
}
