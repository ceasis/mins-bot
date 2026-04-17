package com.minsbot.skills.cashflowforecast;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/cashflowforecast")
public class CashflowForecastController {
    private final CashflowForecastService service;
    private final CashflowForecastConfig.CashflowForecastProperties properties;
    public CashflowForecastController(CashflowForecastService service, CashflowForecastConfig.CashflowForecastProperties properties) { this.service = service; this.properties = properties; }

    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "cashflowforecast", "enabled", properties.isEnabled())); }

    @GetMapping("/project") public ResponseEntity<?> project(@RequestParam double openingBalance, @RequestParam double monthlyInflow, @RequestParam double monthlyOutflow, @RequestParam(defaultValue = "0") double inflowGrowthPct, @RequestParam(defaultValue = "0") double outflowGrowthPct, @RequestParam(defaultValue = "12") int months) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.project(openingBalance, monthlyInflow, monthlyOutflow, inflowGrowthPct, outflowGrowthPct, months)); } catch (IllegalArgumentException e) { return bad(e); }
    }

    @GetMapping("/runway") public ResponseEntity<?> runway(@RequestParam double cash, @RequestParam double monthlyBurn) {
        if (!properties.isEnabled()) return disabled();
        return ResponseEntity.ok(service.runway(cash, monthlyBurn));
    }

    private ResponseEntity<Map<String, Object>> disabled() { return ResponseEntity.status(403).body(Map.of("error", "CashflowForecast skill is disabled")); }
    private ResponseEntity<Map<String, Object>> bad(Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
}
