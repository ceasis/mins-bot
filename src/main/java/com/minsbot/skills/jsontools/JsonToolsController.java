package com.minsbot.skills.jsontools;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/jsontools")
public class JsonToolsController {

    private final JsonToolsService service;
    private final JsonToolsConfig.JsonToolsProperties properties;

    public JsonToolsController(JsonToolsService service, JsonToolsConfig.JsonToolsProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "jsontools",
                "enabled", properties.isEnabled(),
                "maxInputBytes", properties.getMaxInputBytes()
        ));
    }

    @PostMapping("/validate")
    public ResponseEntity<?> validate(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return disabled();
        String input = body.get("input");
        if (input == null) return ResponseEntity.badRequest().body(Map.of("error", "Missing required field: input"));
        if (tooLarge(input)) return ResponseEntity.badRequest().body(Map.of("error", "Input exceeds maxInputBytes"));
        return ResponseEntity.ok(service.validate(input));
    }

    @PostMapping("/pretty")
    public ResponseEntity<?> pretty(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return disabled();
        String input = body.get("input");
        if (input == null) return ResponseEntity.badRequest().body(Map.of("error", "Missing required field: input"));
        if (tooLarge(input)) return ResponseEntity.badRequest().body(Map.of("error", "Input exceeds maxInputBytes"));
        try {
            return ResponseEntity.ok(Map.of("result", service.prettyPrint(input)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/minify")
    public ResponseEntity<?> minify(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return disabled();
        String input = body.get("input");
        if (input == null) return ResponseEntity.badRequest().body(Map.of("error", "Missing required field: input"));
        if (tooLarge(input)) return ResponseEntity.badRequest().body(Map.of("error", "Input exceeds maxInputBytes"));
        try {
            return ResponseEntity.ok(Map.of("result", service.minify(input)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/describe")
    public ResponseEntity<?> describe(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return disabled();
        String input = body.get("input");
        if (input == null) return ResponseEntity.badRequest().body(Map.of("error", "Missing required field: input"));
        if (tooLarge(input)) return ResponseEntity.badRequest().body(Map.of("error", "Input exceeds maxInputBytes"));
        try {
            return ResponseEntity.ok(service.describe(input));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private boolean tooLarge(String input) {
        return input.length() > properties.getMaxInputBytes();
    }

    private ResponseEntity<Map<String, Object>> disabled() {
        return ResponseEntity.status(403).body(Map.of("error", "JsonTools skill is disabled"));
    }
}
