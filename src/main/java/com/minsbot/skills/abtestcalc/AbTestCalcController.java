package com.minsbot.skills.abtestcalc;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/abtestcalc")
public class AbTestCalcController {

    private final AbTestCalcService service;
    private final AbTestCalcConfig.AbTestCalcProperties properties;

    public AbTestCalcController(AbTestCalcService service,
                                AbTestCalcConfig.AbTestCalcProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "abtestcalc",
                "enabled", properties.isEnabled()
        ));
    }

    @GetMapping("/significance")
    public ResponseEntity<?> significance(@RequestParam long visitorsA,
                                          @RequestParam long conversionsA,
                                          @RequestParam long visitorsB,
                                          @RequestParam long conversionsB) {
        if (!properties.isEnabled()) return disabled();
        try {
            return ResponseEntity.ok(service.significance(visitorsA, conversionsA, visitorsB, conversionsB));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/sample-size")
    public ResponseEntity<?> sampleSize(@RequestParam double baselineRate,
                                        @RequestParam double minDetectableEffectPct,
                                        @RequestParam(defaultValue = "95") double confidencePct,
                                        @RequestParam(defaultValue = "80") double powerPct) {
        if (!properties.isEnabled()) return disabled();
        try {
            return ResponseEntity.ok(service.sampleSize(baselineRate, minDetectableEffectPct, confidencePct, powerPct));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<Map<String, Object>> disabled() {
        return ResponseEntity.status(403).body(Map.of("error", "AbTestCalc skill is disabled"));
    }
}
