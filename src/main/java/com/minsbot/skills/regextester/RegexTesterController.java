package com.minsbot.skills.regextester;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/regextester")
public class RegexTesterController {

    private final RegexTesterService service;
    private final RegexTesterConfig.RegexTesterProperties properties;

    public RegexTesterController(RegexTesterService service, RegexTesterConfig.RegexTesterProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "regextester",
                "enabled", properties.isEnabled(),
                "maxInputBytes", properties.getMaxInputBytes(),
                "maxMatches", properties.getMaxMatches(),
                "supportedFlags", "imsxu (case-insensitive, multiline, dotall, comments, unicode-case)"
        ));
    }

    @PostMapping("/test")
    public ResponseEntity<?> test(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return disabled();
        try {
            String pattern = required(body, "pattern");
            String input = required(body, "input");
            String flags = body.getOrDefault("flags", "");
            if (input.length() > properties.getMaxInputBytes()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Input exceeds maxInputBytes"));
            }
            return ResponseEntity.ok(service.test(pattern, flags, input, properties.getMaxMatches()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/replace")
    public ResponseEntity<?> replace(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return disabled();
        try {
            String pattern = requiredStr(body, "pattern");
            String input = requiredStr(body, "input");
            String replacement = (String) body.getOrDefault("replacement", "");
            String flags = (String) body.getOrDefault("flags", "");
            boolean all = Boolean.TRUE.equals(body.getOrDefault("all", true));
            if (input.length() > properties.getMaxInputBytes()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Input exceeds maxInputBytes"));
            }
            return ResponseEntity.ok(service.replace(pattern, flags, input, replacement, all));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private static String required(Map<String, String> body, String key) {
        String value = body.get(key);
        if (value == null) throw new IllegalArgumentException("Missing required field: " + key);
        return value;
    }

    private static String requiredStr(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null) throw new IllegalArgumentException("Missing required field: " + key);
        return value.toString();
    }

    private ResponseEntity<Map<String, Object>> disabled() {
        return ResponseEntity.status(403).body(Map.of("error", "RegexTester skill is disabled"));
    }
}
