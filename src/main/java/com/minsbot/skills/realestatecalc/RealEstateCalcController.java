package com.minsbot.skills.realestatecalc;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/realestatecalc")
public class RealEstateCalcController {
    private final RealEstateCalcService service;
    private final RealEstateCalcConfig.RealEstateCalcProperties properties;

    public RealEstateCalcController(RealEstateCalcService service, RealEstateCalcConfig.RealEstateCalcProperties properties) {
        this.service = service; this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "realestatecalc", "enabled", properties.isEnabled())); }

    @GetMapping("/mortgage")
    public ResponseEntity<?> mortgage(@RequestParam double price, @RequestParam double downPayment,
                                      @RequestParam double annualRatePct, @RequestParam(defaultValue = "30") int termYears,
                                      @RequestParam(required = false) Double annualPropertyTax,
                                      @RequestParam(required = false) Double annualInsurance,
                                      @RequestParam(required = false) Double monthlyHoa) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.mortgage(price, downPayment, annualRatePct, termYears, annualPropertyTax, annualInsurance, monthlyHoa)); }
        catch (IllegalArgumentException e) { return bad(e); }
    }

    @GetMapping("/cap-rate")
    public ResponseEntity<?> capRate(@RequestParam double noi, @RequestParam double propertyValue) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.capRate(noi, propertyValue)); } catch (IllegalArgumentException e) { return bad(e); }
    }

    @GetMapping("/cash-on-cash")
    public ResponseEntity<?> cashOnCash(@RequestParam double annualCashFlow, @RequestParam double totalCashInvested) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.cashOnCash(annualCashFlow, totalCashInvested)); } catch (IllegalArgumentException e) { return bad(e); }
    }

    @GetMapping("/one-percent-rule")
    public ResponseEntity<?> onePercent(@RequestParam double monthlyRent, @RequestParam double purchasePrice) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.one_percent_rule(monthlyRent, purchasePrice)); } catch (IllegalArgumentException e) { return bad(e); }
    }

    private ResponseEntity<Map<String, Object>> disabled() { return ResponseEntity.status(403).body(Map.of("error", "RealEstateCalc skill is disabled")); }
    private ResponseEntity<Map<String, Object>> bad(Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
}
