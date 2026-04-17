package com.minsbot.skills.cronvalidator;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/cronvalidator")
public class CronValidatorController {
    private final CronValidatorService service;
    private final CronValidatorConfig.CronValidatorProperties properties;

    public CronValidatorController(CronValidatorService service, CronValidatorConfig.CronValidatorProperties properties) {
        this.service = service; this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of("skill", "cronvalidator", "enabled", properties.isEnabled(),
                "maxNextRuns", properties.getMaxNextRuns(),
                "format", "Spring 6-field: second minute hour day-of-month month day-of-week"));
    }

    @GetMapping("/validate")
    public ResponseEntity<?> validate(@RequestParam String cron) {
        if (!properties.isEnabled()) return disabled();
        return ResponseEntity.ok(service.validate(cron));
    }

    @GetMapping("/next")
    public ResponseEntity<?> next(@RequestParam String cron,
                                  @RequestParam(defaultValue = "5") int count,
                                  @RequestParam(required = false) String timezone) {
        if (!properties.isEnabled()) return disabled();
        if (count > properties.getMaxNextRuns()) count = properties.getMaxNextRuns();
        try { return ResponseEntity.ok(service.nextRuns(cron, count, timezone)); }
        catch (IllegalArgumentException e) { return bad(e); }
    }

    private ResponseEntity<Map<String, Object>> disabled() { return ResponseEntity.status(403).body(Map.of("error", "CronValidator skill is disabled")); }
    private ResponseEntity<Map<String, Object>> bad(Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
}
