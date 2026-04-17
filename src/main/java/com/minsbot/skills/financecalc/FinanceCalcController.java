package com.minsbot.skills.financecalc;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/financecalc")
public class FinanceCalcController {
    private final FinanceCalcService service;
    private final FinanceCalcConfig.FinanceCalcProperties properties;

    public FinanceCalcController(FinanceCalcService service, FinanceCalcConfig.FinanceCalcProperties properties) {
        this.service = service; this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "financecalc", "enabled", properties.isEnabled())); }

    @GetMapping("/compound")
    public ResponseEntity<?> compound(@RequestParam double principal, @RequestParam double annualRatePct,
                                      @RequestParam int years, @RequestParam(defaultValue = "12") int compoundsPerYear) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.compound(principal, annualRatePct, years, compoundsPerYear)); } catch (IllegalArgumentException e) { return bad(e); }
    }

    @GetMapping("/loan")
    public ResponseEntity<?> loan(@RequestParam double principal, @RequestParam double annualRatePct, @RequestParam int termYears) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.loanPayment(principal, annualRatePct, termYears)); } catch (IllegalArgumentException e) { return bad(e); }
    }

    @PostMapping("/npv")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> npv(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return disabled();
        try {
            double rate = ((Number) body.get("discountRatePct")).doubleValue();
            List<Number> flows = (List<Number>) body.get("cashFlows");
            return ResponseEntity.ok(service.npv(rate, flows.stream().map(Number::doubleValue).toList()));
        } catch (Exception e) { return bad(e); }
    }

    @PostMapping("/irr")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> irr(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return disabled();
        try {
            List<Number> flows = (List<Number>) body.get("cashFlows");
            return ResponseEntity.ok(service.irr(flows.stream().map(Number::doubleValue).toList()));
        } catch (Exception e) { return bad(e); }
    }

    @GetMapping("/roi")
    public ResponseEntity<?> roi(@RequestParam double gain, @RequestParam double cost) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.roi(gain, cost)); } catch (IllegalArgumentException e) { return bad(e); }
    }

    @GetMapping("/present-value")
    public ResponseEntity<?> pv(@RequestParam double futureValue, @RequestParam double annualRatePct, @RequestParam int years) {
        if (!properties.isEnabled()) return disabled();
        return ResponseEntity.ok(service.presentValue(futureValue, annualRatePct, years));
    }

    @GetMapping("/future-value")
    public ResponseEntity<?> fv(@RequestParam double presentValue, @RequestParam double annualRatePct, @RequestParam int years) {
        if (!properties.isEnabled()) return disabled();
        return ResponseEntity.ok(service.futureValue(presentValue, annualRatePct, years));
    }

    private ResponseEntity<Map<String, Object>> disabled() { return ResponseEntity.status(403).body(Map.of("error", "FinanceCalc skill is disabled")); }
    private ResponseEntity<Map<String, Object>> bad(Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
}
