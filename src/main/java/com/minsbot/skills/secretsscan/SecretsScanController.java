package com.minsbot.skills.secretsscan;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/secretsscan")
public class SecretsScanController {

    private final SecretsScanService service;
    private final SecretsScanConfig.SecretsScanProperties properties;

    public SecretsScanController(SecretsScanService service,
                                 SecretsScanConfig.SecretsScanProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "secretsscan",
                "enabled", properties.isEnabled(),
                "maxTextChars", properties.getMaxTextChars(),
                "redactKeepChars", properties.getRedactKeepChars()
        ));
    }

    @PostMapping("/scan")
    public ResponseEntity<?> scan(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return disabled();
        String text = (String) body.get("text");
        if (text == null) return ResponseEntity.badRequest().body(Map.of("error", "text required"));
        if (text.length() > properties.getMaxTextChars()) {
            return ResponseEntity.badRequest().body(Map.of("error", "text exceeds maxTextChars"));
        }
        boolean redact = Boolean.TRUE.equals(body.getOrDefault("redact", true));
        return ResponseEntity.ok(service.scan(text, redact, properties.getRedactKeepChars()));
    }

    private ResponseEntity<Map<String, Object>> disabled() {
        return ResponseEntity.status(403).body(Map.of("error", "SecretsScan skill is disabled"));
    }
}
