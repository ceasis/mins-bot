package com.minsbot.skills.taxcalc;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/taxcalc")
public class TaxCalcController {
    private final TaxCalcService service;
    private final TaxCalcConfig.TaxCalcProperties properties;

    public TaxCalcController(TaxCalcService service, TaxCalcConfig.TaxCalcProperties properties) {
        this.service = service; this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "taxcalc", "enabled", properties.isEnabled())); }

    @PostMapping("/compute")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> compute(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "TaxCalc skill is disabled"));
        try {
            double income = ((Number) body.get("income")).doubleValue();
            List<Map<String, Object>> brackets = (List<Map<String, Object>>) body.get("brackets");
            return ResponseEntity.ok(service.computeBrackets(income, brackets));
        } catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", "invalid request body: " + e.getMessage())); }
    }
}
