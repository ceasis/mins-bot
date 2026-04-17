package com.minsbot.skills.recipescaler;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/recipescaler")
public class RecipeScalerController {
    private final RecipeScalerService service;
    private final RecipeScalerConfig.RecipeScalerProperties properties;

    public RecipeScalerController(RecipeScalerService service, RecipeScalerConfig.RecipeScalerProperties properties) {
        this.service = service; this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "recipescaler", "enabled", properties.isEnabled())); }

    @PostMapping("/scale")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> scale(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return disabled();
        try {
            List<Map<String, Object>> ings = (List<Map<String, Object>>) body.get("ingredients");
            int orig = ((Number) body.get("originalServings")).intValue();
            int target = ((Number) body.get("targetServings")).intValue();
            return ResponseEntity.ok(service.scale(ings, orig, target));
        } catch (Exception e) { return bad(e); }
    }

    @GetMapping("/volume")
    public ResponseEntity<?> volume(@RequestParam double value, @RequestParam String from, @RequestParam String to) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.convertVolume(value, from, to)); } catch (IllegalArgumentException e) { return bad(e); }
    }

    @GetMapping("/weight")
    public ResponseEntity<?> weight(@RequestParam double value, @RequestParam String from, @RequestParam String to) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.convertWeight(value, from, to)); } catch (IllegalArgumentException e) { return bad(e); }
    }

    @GetMapping("/oven")
    public ResponseEntity<?> oven(@RequestParam double value, @RequestParam String from, @RequestParam String to) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.ovenTemp(value, from, to)); } catch (IllegalArgumentException e) { return bad(e); }
    }

    private ResponseEntity<Map<String, Object>> disabled() { return ResponseEntity.status(403).body(Map.of("error", "RecipeScaler skill is disabled")); }
    private ResponseEntity<Map<String, Object>> bad(Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
}
