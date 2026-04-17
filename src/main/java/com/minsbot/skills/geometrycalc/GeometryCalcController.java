package com.minsbot.skills.geometrycalc;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/geometrycalc")
public class GeometryCalcController {
    private final GeometryCalcService service;
    private final GeometryCalcConfig.GeometryCalcProperties properties;

    public GeometryCalcController(GeometryCalcService service, GeometryCalcConfig.GeometryCalcProperties properties) {
        this.service = service; this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "geometrycalc", "enabled", properties.isEnabled())); }

    @PostMapping("/2d")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> shape2d(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return disabled();
        try {
            String shape = (String) body.get("shape");
            Map<String, Double> dims = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, Object> e : body.entrySet()) {
                if (!"shape".equals(e.getKey()) && e.getValue() instanceof Number n) dims.put(e.getKey(), n.doubleValue());
            }
            return ResponseEntity.ok(service.shape2d(shape, dims));
        } catch (IllegalArgumentException e) { return bad(e); }
    }

    @PostMapping("/3d")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> shape3d(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return disabled();
        try {
            String shape = (String) body.get("shape");
            Map<String, Double> dims = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, Object> e : body.entrySet()) {
                if (!"shape".equals(e.getKey()) && e.getValue() instanceof Number n) dims.put(e.getKey(), n.doubleValue());
            }
            return ResponseEntity.ok(service.shape3d(shape, dims));
        } catch (IllegalArgumentException e) { return bad(e); }
    }

    @GetMapping("/distance")
    public ResponseEntity<?> distance(@RequestParam double x1, @RequestParam double y1,
                                      @RequestParam double x2, @RequestParam double y2) {
        if (!properties.isEnabled()) return disabled();
        return ResponseEntity.ok(service.distance(x1, y1, x2, y2));
    }

    private ResponseEntity<Map<String, Object>> disabled() { return ResponseEntity.status(403).body(Map.of("error", "GeometryCalc skill is disabled")); }
    private ResponseEntity<Map<String, Object>> bad(Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
}
