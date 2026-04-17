package com.minsbot.skills.heartratezones;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/heartratezones")
public class HeartRateZonesController {
    private final HeartRateZonesService service;
    private final HeartRateZonesConfig.HeartRateZonesProperties properties;
    public HeartRateZonesController(HeartRateZonesService service, HeartRateZonesConfig.HeartRateZonesProperties properties) { this.service = service; this.properties = properties; }

    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "heartratezones", "enabled", properties.isEnabled())); }

    @GetMapping("/zones") public ResponseEntity<?> zones(@RequestParam int age, @RequestParam(required = false) Integer restingHr) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.zones(age, restingHr)); } catch (IllegalArgumentException e) { return bad(e); }
    }

    private ResponseEntity<Map<String, Object>> disabled() { return ResponseEntity.status(403).body(Map.of("error", "HeartRateZones skill is disabled")); }
    private ResponseEntity<Map<String, Object>> bad(Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
}
