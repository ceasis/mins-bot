package com.minsbot.skills.randomgen;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/randomgen")
public class RandomGenController {

    private final RandomGenService service;
    private final RandomGenConfig.RandomGenProperties properties;

    public RandomGenController(RandomGenService service, RandomGenConfig.RandomGenProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "randomgen",
                "enabled", properties.isEnabled(),
                "maxCount", properties.getMaxCount(),
                "maxStringLength", properties.getMaxStringLength()
        ));
    }

    @GetMapping("/uuid")
    public ResponseEntity<?> uuid(@RequestParam(defaultValue = "1") int count) {
        if (!properties.isEnabled()) return disabled();
        if (count < 1 || count > properties.getMaxCount()) {
            return ResponseEntity.badRequest().body(Map.of("error", "count out of range"));
        }
        return ResponseEntity.ok(Map.of("uuids", service.uuids(count)));
    }

    @GetMapping("/int")
    public ResponseEntity<?> integers(@RequestParam(defaultValue = "1") int count,
                                      @RequestParam(defaultValue = "0") long min,
                                      @RequestParam(defaultValue = "100") long max) {
        if (!properties.isEnabled()) return disabled();
        if (count < 1 || count > properties.getMaxCount()) {
            return ResponseEntity.badRequest().body(Map.of("error", "count out of range"));
        }
        try {
            return ResponseEntity.ok(Map.of("values", service.integers(count, min, max)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/dice")
    public ResponseEntity<?> dice(@RequestParam(defaultValue = "1") int count,
                                  @RequestParam(defaultValue = "6") int sides) {
        if (!properties.isEnabled()) return disabled();
        if (count < 1 || count > properties.getMaxCount()) {
            return ResponseEntity.badRequest().body(Map.of("error", "count out of range"));
        }
        try {
            List<Integer> rolls = service.dice(count, sides);
            return ResponseEntity.ok(Map.of(
                    "rolls", rolls,
                    "sum", rolls.stream().mapToInt(Integer::intValue).sum()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/password")
    public ResponseEntity<?> password(@RequestParam(defaultValue = "1") int count,
                                      @RequestParam(defaultValue = "16") int length,
                                      @RequestParam(defaultValue = "true") boolean upper,
                                      @RequestParam(defaultValue = "true") boolean digits,
                                      @RequestParam(defaultValue = "true") boolean symbols) {
        if (!properties.isEnabled()) return disabled();
        if (count < 1 || count > properties.getMaxCount()) {
            return ResponseEntity.badRequest().body(Map.of("error", "count out of range"));
        }
        if (length > properties.getMaxStringLength()) {
            return ResponseEntity.badRequest().body(Map.of("error", "length exceeds maxStringLength"));
        }
        try {
            return ResponseEntity.ok(Map.of("passwords", service.passwords(count, length, upper, digits, symbols)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/choose")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> choose(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return disabled();
        try {
            List<String> options = (List<String>) body.get("options");
            int count = ((Number) body.getOrDefault("count", 1)).intValue();
            boolean unique = Boolean.TRUE.equals(body.getOrDefault("unique", false));
            if (count < 1 || count > properties.getMaxCount()) {
                return ResponseEntity.badRequest().body(Map.of("error", "count out of range"));
            }
            return ResponseEntity.ok(Map.of("chosen", service.choose(options, count, unique)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<Map<String, Object>> disabled() {
        return ResponseEntity.status(403).body(Map.of("error", "RandomGen skill is disabled"));
    }
}
