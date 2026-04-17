package com.minsbot.skills.physicscalc;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/physicscalc")
public class PhysicsCalcController {
    private final PhysicsCalcService service;
    private final PhysicsCalcConfig.PhysicsCalcProperties properties;
    public PhysicsCalcController(PhysicsCalcService service, PhysicsCalcConfig.PhysicsCalcProperties properties) { this.service = service; this.properties = properties; }

    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "physicscalc", "enabled", properties.isEnabled())); }

    @GetMapping("/velocity") public ResponseEntity<?> velocity(@RequestParam double u, @RequestParam double a, @RequestParam double t) { if (!properties.isEnabled()) return disabled(); return ResponseEntity.ok(service.kinematicVelocity(u, a, t)); }
    @GetMapping("/displacement") public ResponseEntity<?> displacement(@RequestParam double u, @RequestParam double a, @RequestParam double t) { if (!properties.isEnabled()) return disabled(); return ResponseEntity.ok(service.kinematicDisplacement(u, a, t)); }
    @GetMapping("/kinetic") public ResponseEntity<?> ke(@RequestParam double mass, @RequestParam double velocity) { if (!properties.isEnabled()) return disabled(); return ResponseEntity.ok(service.kineticEnergy(mass, velocity)); }
    @GetMapping("/potential") public ResponseEntity<?> pe(@RequestParam double mass, @RequestParam double height, @RequestParam(defaultValue = "9.80665") double g) { if (!properties.isEnabled()) return disabled(); return ResponseEntity.ok(service.potentialEnergy(mass, height, g)); }
    @GetMapping("/force") public ResponseEntity<?> force(@RequestParam double mass, @RequestParam double acceleration) { if (!properties.isEnabled()) return disabled(); return ResponseEntity.ok(service.force(mass, acceleration)); }
    @GetMapping("/power") public ResponseEntity<?> power(@RequestParam double work, @RequestParam double seconds) { if (!properties.isEnabled()) return disabled(); try { return ResponseEntity.ok(service.power(work, seconds)); } catch (IllegalArgumentException e) { return bad(e); } }
    @GetMapping("/pressure") public ResponseEntity<?> pressure(@RequestParam double force, @RequestParam double area) { if (!properties.isEnabled()) return disabled(); try { return ResponseEntity.ok(service.pressure(force, area)); } catch (IllegalArgumentException e) { return bad(e); } }
    @GetMapping("/ohm") public ResponseEntity<?> ohm(@RequestParam(required = false) Double voltage, @RequestParam(required = false) Double current, @RequestParam(required = false) Double resistance) { if (!properties.isEnabled()) return disabled(); try { return ResponseEntity.ok(service.ohmsLaw(voltage, current, resistance)); } catch (IllegalArgumentException e) { return bad(e); } }

    private ResponseEntity<Map<String, Object>> disabled() { return ResponseEntity.status(403).body(Map.of("error", "PhysicsCalc skill is disabled")); }
    private ResponseEntity<Map<String, Object>> bad(Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
}
