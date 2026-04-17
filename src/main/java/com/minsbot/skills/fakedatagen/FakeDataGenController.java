package com.minsbot.skills.fakedatagen;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/fakedatagen")
public class FakeDataGenController {
    private final FakeDataGenService service;
    private final FakeDataGenConfig.FakeDataGenProperties properties;
    public FakeDataGenController(FakeDataGenService service, FakeDataGenConfig.FakeDataGenProperties properties) { this.service = service; this.properties = properties; }

    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "fakedatagen", "enabled", properties.isEnabled(), "maxRows", properties.getMaxRows(), "supportedFields", service.supportedFields())); }

    @PostMapping("/generate") @SuppressWarnings("unchecked") public ResponseEntity<?> generate(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "FakeDataGen skill is disabled"));
        try {
            List<String> fields = (List<String>) body.get("fields");
            int count = body.get("count") == null ? 10 : ((Number) body.get("count")).intValue();
            return ResponseEntity.ok(service.generate(fields, count, properties.getMaxRows()));
        } catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
