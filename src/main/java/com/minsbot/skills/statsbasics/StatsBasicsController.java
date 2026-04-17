package com.minsbot.skills.statsbasics;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/statsbasics")
public class StatsBasicsController {
    private final StatsBasicsService service;
    private final StatsBasicsConfig.StatsBasicsProperties properties;

    public StatsBasicsController(StatsBasicsService service, StatsBasicsConfig.StatsBasicsProperties properties) {
        this.service = service; this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of("skill", "statsbasics", "enabled", properties.isEnabled(),
                "maxValues", properties.getMaxValues()));
    }

    @PostMapping("/describe")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> describe(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "StatsBasics skill is disabled"));
        List<Number> values = (List<Number>) body.get("values");
        if (values == null) return ResponseEntity.badRequest().body(Map.of("error", "values (array) required"));
        if (values.size() > properties.getMaxValues()) return ResponseEntity.badRequest().body(Map.of("error", "values exceeds maxValues"));
        try {
            return ResponseEntity.ok(service.describe(values.stream().map(Number::doubleValue).toList()));
        } catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    @PostMapping("/correlation")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> correlation(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "StatsBasics skill is disabled"));
        List<Number> x = (List<Number>) body.get("x");
        List<Number> y = (List<Number>) body.get("y");
        if (x == null || y == null) return ResponseEntity.badRequest().body(Map.of("error", "x and y required"));
        try {
            return ResponseEntity.ok(service.correlation(x.stream().map(Number::doubleValue).toList(), y.stream().map(Number::doubleValue).toList()));
        } catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
