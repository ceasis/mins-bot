package com.minsbot.skills.macrocalc;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/macrocalc")
public class MacroCalcController {
    private final MacroCalcService service;
    private final MacroCalcConfig.MacroCalcProperties properties;
    public MacroCalcController(MacroCalcService service, MacroCalcConfig.MacroCalcProperties properties) { this.service = service; this.properties = properties; }

    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "macrocalc", "enabled", properties.isEnabled())); }

    @GetMapping("/from-calories") public ResponseEntity<?> fromCal(@RequestParam double calories, @RequestParam(defaultValue = "balanced") String goal) {
        if (!properties.isEnabled()) return disabled();
        return ResponseEntity.ok(service.computeFromCalories(calories, goal));
    }

    @GetMapping("/from-weight") public ResponseEntity<?> fromWeight(@RequestParam double weightKg, @RequestParam(defaultValue = "1.8") double proteinPerKg, @RequestParam(defaultValue = "1.0") double fatPerKg, @RequestParam double targetKcal) {
        if (!properties.isEnabled()) return disabled();
        return ResponseEntity.ok(service.computeFromWeight(weightKg, proteinPerKg, fatPerKg, targetKcal));
    }

    private ResponseEntity<Map<String, Object>> disabled() { return ResponseEntity.status(403).body(Map.of("error", "MacroCalc skill is disabled")); }
}
