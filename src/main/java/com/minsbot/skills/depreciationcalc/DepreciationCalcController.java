package com.minsbot.skills.depreciationcalc;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/depreciationcalc")
public class DepreciationCalcController {
    private final DepreciationCalcService service;
    private final DepreciationCalcConfig.DepreciationCalcProperties properties;
    public DepreciationCalcController(DepreciationCalcService service, DepreciationCalcConfig.DepreciationCalcProperties properties) { this.service = service; this.properties = properties; }

    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "depreciationcalc", "enabled", properties.isEnabled())); }

    @GetMapping("/straight-line") public ResponseEntity<?> straightLine(@RequestParam double cost, @RequestParam double salvage, @RequestParam int usefulYears) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.straightLine(cost, salvage, usefulYears)); } catch (IllegalArgumentException e) { return bad(e); }
    }

    @GetMapping("/declining-balance") public ResponseEntity<?> declining(@RequestParam double cost, @RequestParam double salvage, @RequestParam int usefulYears, @RequestParam(defaultValue = "2") double rateMultiplier) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.decliningBalance(cost, salvage, usefulYears, rateMultiplier)); } catch (IllegalArgumentException e) { return bad(e); }
    }

    @GetMapping("/sum-of-years") public ResponseEntity<?> soyd(@RequestParam double cost, @RequestParam double salvage, @RequestParam int usefulYears) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.sumOfYearsDigits(cost, salvage, usefulYears)); } catch (IllegalArgumentException e) { return bad(e); }
    }

    @PostMapping("/units") @SuppressWarnings("unchecked") public ResponseEntity<?> units(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return disabled();
        try {
            double cost = ((Number) body.get("cost")).doubleValue();
            double salvage = ((Number) body.get("salvage")).doubleValue();
            double totalUnits = ((Number) body.get("totalExpectedUnits")).doubleValue();
            List<Integer> perYear = (List<Integer>) body.get("unitsPerYear");
            return ResponseEntity.ok(service.unitsOfProduction(cost, salvage, totalUnits, perYear));
        } catch (Exception e) { return bad(e); }
    }

    private ResponseEntity<Map<String, Object>> disabled() { return ResponseEntity.status(403).body(Map.of("error", "DepreciationCalc skill is disabled")); }
    private ResponseEntity<Map<String, Object>> bad(Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
}
