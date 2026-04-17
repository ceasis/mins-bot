package com.minsbot.skills.hashidentifier;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/hashidentifier")
public class HashIdentifierController {

    private final HashIdentifierService service;
    private final HashIdentifierConfig.HashIdentifierProperties properties;

    public HashIdentifierController(HashIdentifierService service,
                                    HashIdentifierConfig.HashIdentifierProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "hashidentifier",
                "enabled", properties.isEnabled(),
                "maxInputLength", properties.getMaxInputLength()
        ));
    }

    @PostMapping("/identify")
    public ResponseEntity<?> identify(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return disabled();
        String input = body.get("input");
        if (input == null) return ResponseEntity.badRequest().body(Map.of("error", "input required"));
        if (input.length() > properties.getMaxInputLength()) {
            return ResponseEntity.badRequest().body(Map.of("error", "input exceeds maxInputLength"));
        }
        return ResponseEntity.ok(service.identify(input));
    }

    private ResponseEntity<Map<String, Object>> disabled() {
        return ResponseEntity.status(403).body(Map.of("error", "HashIdentifier skill is disabled"));
    }
}
