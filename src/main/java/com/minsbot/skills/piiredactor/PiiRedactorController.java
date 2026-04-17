package com.minsbot.skills.piiredactor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/piiredactor")
public class PiiRedactorController {
    private final PiiRedactorService service;
    private final PiiRedactorConfig.PiiRedactorProperties properties;
    public PiiRedactorController(PiiRedactorService service, PiiRedactorConfig.PiiRedactorProperties properties) { this.service = service; this.properties = properties; }

    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "piiredactor", "enabled", properties.isEnabled(), "supportedTypes", service.supportedTypes())); }

    @PostMapping("/redact") @SuppressWarnings("unchecked") public ResponseEntity<?> redact(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return disabled();
        String text = (String) body.get("text"); if (text == null) return bad("text required");
        if (text.length() > properties.getMaxTextChars()) return bad("exceeds maxTextChars");
        List<String> types = (List<String>) body.get("types");
        return ResponseEntity.ok(service.redact(text, types));
    }

    private ResponseEntity<Map<String, Object>> disabled() { return ResponseEntity.status(403).body(Map.of("error", "PiiRedactor skill is disabled")); }
    private ResponseEntity<Map<String, Object>> bad(String msg) { return ResponseEntity.badRequest().body(Map.of("error", msg)); }
}
