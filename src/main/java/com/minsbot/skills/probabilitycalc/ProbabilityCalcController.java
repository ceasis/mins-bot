package com.minsbot.skills.probabilitycalc;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/probabilitycalc")
public class ProbabilityCalcController {
    private final ProbabilityCalcService service;
    private final ProbabilityCalcConfig.ProbabilityCalcProperties properties;
    public ProbabilityCalcController(ProbabilityCalcService service, ProbabilityCalcConfig.ProbabilityCalcProperties properties) { this.service = service; this.properties = properties; }

    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "probabilitycalc", "enabled", properties.isEnabled())); }

    @GetMapping("/factorial") public ResponseEntity<?> factorial(@RequestParam long n) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.factorial(n)); } catch (IllegalArgumentException e) { return bad(e); }
    }

    @GetMapping("/combinations") public ResponseEntity<?> combinations(@RequestParam long n, @RequestParam long k) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.combinations(n, k)); } catch (IllegalArgumentException e) { return bad(e); }
    }

    @GetMapping("/permutations") public ResponseEntity<?> permutations(@RequestParam long n, @RequestParam long k) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.permutations(n, k)); } catch (IllegalArgumentException e) { return bad(e); }
    }

    @GetMapping("/binomial") public ResponseEntity<?> binomial(@RequestParam int n, @RequestParam int k, @RequestParam double p) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.binomial(n, k, p)); } catch (IllegalArgumentException e) { return bad(e); }
    }

    @GetMapping("/normal") public ResponseEntity<?> normal(@RequestParam double x, @RequestParam(defaultValue = "0") double mean, @RequestParam(defaultValue = "1") double stdev) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.normal(x, mean, stdev)); } catch (IllegalArgumentException e) { return bad(e); }
    }

    @GetMapping("/poisson") public ResponseEntity<?> poisson(@RequestParam int k, @RequestParam double lambda) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.poisson(k, lambda)); } catch (IllegalArgumentException e) { return bad(e); }
    }

    private ResponseEntity<Map<String, Object>> disabled() { return ResponseEntity.status(403).body(Map.of("error", "ProbabilityCalc skill is disabled")); }
    private ResponseEntity<Map<String, Object>> bad(Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
}
