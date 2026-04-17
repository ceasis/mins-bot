package com.minsbot.skills.charcounter;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/charcounter")
public class CharCounterController {

    private final CharCounterService service;
    private final CharCounterConfig.CharCounterProperties properties;

    public CharCounterController(CharCounterService service,
                                 CharCounterConfig.CharCounterProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "charcounter",
                "enabled", properties.isEnabled(),
                "maxTextChars", properties.getMaxTextChars(),
                "supportedPlatforms", service.supportedPlatforms()
        ));
    }

    @PostMapping("/count")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> count(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return disabled();
        String text = (String) body.get("text");
        if (text == null) return ResponseEntity.badRequest().body(Map.of("error", "text required"));
        if (text.length() > properties.getMaxTextChars()) {
            return ResponseEntity.badRequest().body(Map.of("error", "text exceeds maxTextChars"));
        }
        List<String> platforms = (List<String>) body.get("platforms");
        return ResponseEntity.ok(service.count(text, platforms));
    }

    private ResponseEntity<Map<String, Object>> disabled() {
        return ResponseEntity.status(403).body(Map.of("error", "CharCounter skill is disabled"));
    }
}
