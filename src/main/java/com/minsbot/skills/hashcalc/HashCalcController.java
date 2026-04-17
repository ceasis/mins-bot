package com.minsbot.skills.hashcalc;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/hashcalc")
public class HashCalcController {

    private final HashCalcService service;
    private final HashCalcConfig.HashCalcProperties properties;

    public HashCalcController(HashCalcService service, HashCalcConfig.HashCalcProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "hashcalc",
                "enabled", properties.isEnabled(),
                "maxFileBytes", properties.getMaxFileBytes(),
                "algorithms", service.supportedAlgorithms()
        ));
    }

    @PostMapping("/string")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> hashString(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return disabled();
        try {
            String input = (String) body.get("input");
            if (input == null) throw new IllegalArgumentException("Missing required field: input");
            List<String> algorithms = (List<String>) body.get("algorithms");
            return ResponseEntity.ok(Map.of("hashes", service.hashString(input, algorithms)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/file")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> hashFile(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return disabled();
        try {
            String path = (String) body.get("path");
            List<String> algorithms = (List<String>) body.get("algorithms");
            return ResponseEntity.ok(service.hashFile(path, algorithms, properties.getMaxFileBytes()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/verify")
    public ResponseEntity<?> verify(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return disabled();
        try {
            String input = body.get("input");
            String algorithm = body.getOrDefault("algorithm", "SHA-256");
            String expected = body.get("expected");
            if (input == null || expected == null) {
                throw new IllegalArgumentException("Missing required fields: input, expected");
            }
            boolean match = service.verify(input, algorithm, expected);
            return ResponseEntity.ok(Map.of("algorithm", algorithm, "match", match));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<Map<String, Object>> disabled() {
        return ResponseEntity.status(403).body(Map.of("error", "HashCalc skill is disabled"));
    }
}
