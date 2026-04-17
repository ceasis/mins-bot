package com.minsbot.skills.bmicalc;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/bmicalc")
public class BmiCalcController {
    private final BmiCalcService service;
    private final BmiCalcConfig.BmiCalcProperties properties;

    public BmiCalcController(BmiCalcService service, BmiCalcConfig.BmiCalcProperties properties) {
        this.service = service; this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "bmicalc", "enabled", properties.isEnabled())); }

    @GetMapping("/bmi")
    public ResponseEntity<?> bmi(@RequestParam double weightKg, @RequestParam double heightCm) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.bmi(weightKg, heightCm)); } catch (IllegalArgumentException e) { return bad(e); }
    }

    @GetMapping("/bmr")
    public ResponseEntity<?> bmr(@RequestParam double weightKg, @RequestParam double heightCm,
                                 @RequestParam int age, @RequestParam String sex) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.bmr(weightKg, heightCm, age, sex)); } catch (IllegalArgumentException e) { return bad(e); }
    }

    @GetMapping("/tdee")
    public ResponseEntity<?> tdee(@RequestParam double weightKg, @RequestParam double heightCm,
                                  @RequestParam int age, @RequestParam String sex,
                                  @RequestParam(defaultValue = "moderate") String activityLevel) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.tdee(weightKg, heightCm, age, sex, activityLevel)); } catch (IllegalArgumentException e) { return bad(e); }
    }

    @GetMapping("/body-fat")
    public ResponseEntity<?> bodyFat(@RequestParam String sex, @RequestParam double heightCm,
                                     @RequestParam double neckCm, @RequestParam double waistCm,
                                     @RequestParam(required = false) Double hipCm) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.bodyFatNavy(sex, heightCm, neckCm, waistCm, hipCm)); } catch (IllegalArgumentException e) { return bad(e); }
    }

    private ResponseEntity<Map<String, Object>> disabled() { return ResponseEntity.status(403).body(Map.of("error", "BmiCalc skill is disabled")); }
    private ResponseEntity<Map<String, Object>> bad(Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
}
