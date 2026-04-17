package com.minsbot.skills.slacalc;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/slacalc")
public class SlaCalcController {
    private final SlaCalcService service;
    private final SlaCalcConfig.SlaCalcProperties properties;

    public SlaCalcController(SlaCalcService service, SlaCalcConfig.SlaCalcProperties properties) {
        this.service = service; this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "slacalc", "enabled", properties.isEnabled())); }

    @GetMapping("/uptime")
    public ResponseEntity<?> uptime(@RequestParam double pct) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.uptimeToDowntime(pct)); } catch (IllegalArgumentException e) { return bad(e); }
    }

    @GetMapping("/downtime")
    public ResponseEntity<?> downtime(@RequestParam long seconds, @RequestParam(defaultValue = "year") String period) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.downtimeToUptime(seconds, period)); } catch (IllegalArgumentException e) { return bad(e); }
    }

    @PostMapping("/composite")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> composite(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return disabled();
        try {
            List<Number> ups = (List<Number>) body.get("componentUptimes");
            return ResponseEntity.ok(service.compositeSla(ups.stream().map(Number::doubleValue).toList()));
        } catch (Exception e) { return bad(e); }
    }

    private ResponseEntity<Map<String, Object>> disabled() { return ResponseEntity.status(403).body(Map.of("error", "SlaCalc skill is disabled")); }
    private ResponseEntity<Map<String, Object>> bad(Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
}
