package com.minsbot.skills.medicalunits;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/medicalunits")
public class MedicalUnitsController {
    private final MedicalUnitsService service;
    private final MedicalUnitsConfig.MedicalUnitsProperties properties;

    public MedicalUnitsController(MedicalUnitsService service, MedicalUnitsConfig.MedicalUnitsProperties properties) {
        this.service = service; this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of("skill", "medicalunits", "enabled", properties.isEnabled(),
                "analytes", service.supportedAnalytes()));
    }

    @GetMapping("/lab")
    public ResponseEntity<?> lab(@RequestParam String analyte, @RequestParam double value,
                                 @RequestParam String from, @RequestParam String to) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.convert(analyte, value, from, to)); } catch (IllegalArgumentException e) { return bad(e); }
    }

    @GetMapping("/temperature")
    public ResponseEntity<?> temp(@RequestParam double value, @RequestParam String from, @RequestParam String to) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.tempConvert(value, from, to)); } catch (IllegalArgumentException e) { return bad(e); }
    }

    private ResponseEntity<Map<String, Object>> disabled() { return ResponseEntity.status(403).body(Map.of("error", "MedicalUnits skill is disabled")); }
    private ResponseEntity<Map<String, Object>> bad(Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
}
