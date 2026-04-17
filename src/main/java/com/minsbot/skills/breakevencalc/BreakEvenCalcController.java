package com.minsbot.skills.breakevencalc;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/breakevencalc")
public class BreakEvenCalcController {
    private final BreakEvenCalcService service;
    private final BreakEvenCalcConfig.BreakEvenCalcProperties properties;
    public BreakEvenCalcController(BreakEvenCalcService service, BreakEvenCalcConfig.BreakEvenCalcProperties properties) { this.service = service; this.properties = properties; }

    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "breakevencalc", "enabled", properties.isEnabled())); }

    @GetMapping("/compute") public ResponseEntity<?> compute(@RequestParam double fixedCosts, @RequestParam double pricePerUnit, @RequestParam double variableCostPerUnit) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.compute(fixedCosts, pricePerUnit, variableCostPerUnit)); } catch (IllegalArgumentException e) { return bad(e); }
    }

    @GetMapping("/target-profit") public ResponseEntity<?> targetProfit(@RequestParam double fixedCosts, @RequestParam double pricePerUnit, @RequestParam double variableCostPerUnit, @RequestParam double targetProfit) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.targetProfit(fixedCosts, pricePerUnit, variableCostPerUnit, targetProfit)); } catch (IllegalArgumentException e) { return bad(e); }
    }

    @GetMapping("/safety-margin") public ResponseEntity<?> safetyMargin(@RequestParam double actualRevenue, @RequestParam double breakEvenRevenue) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.safetyMargin(actualRevenue, breakEvenRevenue)); } catch (IllegalArgumentException e) { return bad(e); }
    }

    private ResponseEntity<Map<String, Object>> disabled() { return ResponseEntity.status(403).body(Map.of("error", "BreakEvenCalc skill is disabled")); }
    private ResponseEntity<Map<String, Object>> bad(Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
}
