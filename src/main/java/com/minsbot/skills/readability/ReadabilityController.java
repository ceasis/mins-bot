package com.minsbot.skills.readability;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/readability")
public class ReadabilityController {

    private final ReadabilityService service;
    private final ReadabilityConfig.ReadabilityProperties properties;

    public ReadabilityController(ReadabilityService service,
                                 ReadabilityConfig.ReadabilityProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "readability",
                "enabled", properties.isEnabled(),
                "maxTextChars", properties.getMaxTextChars()
        ));
    }

    @PostMapping("/analyze")
    public ResponseEntity<?> analyze(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return disabled();
        String text = body.get("text");
        if (text == null) return ResponseEntity.badRequest().body(Map.of("error", "text required"));
        return ResponseEntity.ok(service.analyze(text, properties.getMaxTextChars()));
    }

    private ResponseEntity<Map<String, Object>> disabled() {
        return ResponseEntity.status(403).body(Map.of("error", "Readability skill is disabled"));
    }
}
