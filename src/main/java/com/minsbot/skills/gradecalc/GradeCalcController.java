package com.minsbot.skills.gradecalc;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/gradecalc")
public class GradeCalcController {
    private final GradeCalcService service;
    private final GradeCalcConfig.GradeCalcProperties properties;

    public GradeCalcController(GradeCalcService service, GradeCalcConfig.GradeCalcProperties properties) {
        this.service = service; this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "gradecalc", "enabled", properties.isEnabled())); }

    @PostMapping("/weighted")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> weighted(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.weighted((List<Map<String, Object>>) body.get("items"))); }
        catch (IllegalArgumentException e) { return bad(e); } catch (Exception e) { return bad(e); }
    }

    @GetMapping("/needed")
    public ResponseEntity<?> needed(@RequestParam double currentGrade, @RequestParam double currentWeight,
                                    @RequestParam double targetGrade, @RequestParam double remainingWeight) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.needed(currentGrade, currentWeight, targetGrade, remainingWeight)); }
        catch (IllegalArgumentException e) { return bad(e); }
    }

    @PostMapping("/gpa")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> gpa(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return disabled();
        try {
            List<Map<String, Object>> courses = (List<Map<String, Object>>) body.get("courses");
            String scale = String.valueOf(body.getOrDefault("scale", "4.0"));
            return ResponseEntity.ok(service.gpa(courses, scale));
        } catch (Exception e) { return bad(e); }
    }

    private ResponseEntity<Map<String, Object>> disabled() { return ResponseEntity.status(403).body(Map.of("error", "GradeCalc skill is disabled")); }
    private ResponseEntity<Map<String, Object>> bad(Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
}
