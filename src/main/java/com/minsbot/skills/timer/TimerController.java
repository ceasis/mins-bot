package com.minsbot.skills.timer;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/timer")
public class TimerController {

    private final TimerService service;
    private final TimerConfig.TimerProperties properties;

    public TimerController(TimerService service, TimerConfig.TimerProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "timer",
                "enabled", properties.isEnabled(),
                "maxTimers", properties.getMaxTimers(),
                "activeTimers", service.size()
        ));
    }

    @PostMapping("/start")
    public ResponseEntity<?> start(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return disabled();
        if (service.size() >= properties.getMaxTimers()) {
            return ResponseEntity.badRequest().body(Map.of("error", "maxTimers reached"));
        }
        try {
            String name = (String) body.getOrDefault("name", "");
            long durationMs;
            if (body.get("durationMs") != null) {
                durationMs = ((Number) body.get("durationMs")).longValue();
            } else if (body.get("seconds") != null) {
                durationMs = ((Number) body.get("seconds")).longValue() * 1000L;
            } else {
                return ResponseEntity.badRequest().body(Map.of("error", "durationMs or seconds required"));
            }
            return ResponseEntity.ok(service.start(name, durationMs));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<?> pause(@PathVariable String id) {
        if (!properties.isEnabled()) return disabled();
        try {
            return ResponseEntity.ok(service.pause(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<?> resume(@PathVariable String id) {
        if (!properties.isEnabled()) return disabled();
        try {
            return ResponseEntity.ok(service.resume(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> cancel(@PathVariable String id) {
        if (!properties.isEnabled()) return disabled();
        try {
            return ResponseEntity.ok(service.cancel(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        if (!properties.isEnabled()) return disabled();
        try {
            return ResponseEntity.ok(service.get(id, true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<?> list() {
        if (!properties.isEnabled()) return disabled();
        return ResponseEntity.ok(Map.of("timers", service.list()));
    }

    private ResponseEntity<Map<String, Object>> disabled() {
        return ResponseEntity.status(403).body(Map.of("error", "Timer skill is disabled"));
    }
}
