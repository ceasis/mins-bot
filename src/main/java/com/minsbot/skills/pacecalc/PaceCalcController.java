package com.minsbot.skills.pacecalc;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/pacecalc")
public class PaceCalcController {
    private final PaceCalcService service;
    private final PaceCalcConfig.PaceCalcProperties properties;
    public PaceCalcController(PaceCalcService service, PaceCalcConfig.PaceCalcProperties properties) { this.service = service; this.properties = properties; }

    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "pacecalc", "enabled", properties.isEnabled())); }

    @GetMapping("/from-time") public ResponseEntity<?> fromTime(@RequestParam double distanceKm, @RequestParam(defaultValue = "0") int hours, @RequestParam int minutes, @RequestParam(defaultValue = "0") int seconds) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.fromTime(distanceKm, hours, minutes, seconds)); } catch (IllegalArgumentException e) { return bad(e); }
    }

    @GetMapping("/from-pace") public ResponseEntity<?> fromPace(@RequestParam double distanceKm, @RequestParam int paceMin, @RequestParam(defaultValue = "0") int paceSec) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.fromPace(distanceKm, paceMin, paceSec)); } catch (IllegalArgumentException e) { return bad(e); }
    }

    @GetMapping("/splits") public ResponseEntity<?> splits(@RequestParam double distanceKm, @RequestParam int paceMin, @RequestParam(defaultValue = "0") int paceSec) {
        if (!properties.isEnabled()) return disabled();
        return ResponseEntity.ok(service.splits(distanceKm, paceMin, paceSec));
    }

    @GetMapping("/convert") public ResponseEntity<?> convert(@RequestParam int min, @RequestParam(defaultValue = "0") int sec, @RequestParam String from, @RequestParam String to) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.paceConvert(min, sec, from, to)); } catch (IllegalArgumentException e) { return bad(e); }
    }

    private ResponseEntity<Map<String, Object>> disabled() { return ResponseEntity.status(403).body(Map.of("error", "PaceCalc skill is disabled")); }
    private ResponseEntity<Map<String, Object>> bad(Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
}
