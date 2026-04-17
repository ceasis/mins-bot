package com.minsbot.skills.reminders;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/reminders")
public class RemindersController {

    private final RemindersService service;
    private final RemindersConfig.RemindersProperties properties;

    public RemindersController(RemindersService service, RemindersConfig.RemindersProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "reminders",
                "enabled", properties.isEnabled(),
                "storageDir", properties.getStorageDir(),
                "pollIntervalSeconds", properties.getPollIntervalSeconds()
        ));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return disabled();
        try {
            String message = body.get("message");
            String fireAt = body.get("fireAt");
            if (message == null || fireAt == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "message and fireAt required"));
            }
            return ResponseEntity.ok(service.create(message, fireAt));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(defaultValue = "false") boolean includeFired) {
        if (!properties.isEnabled()) return disabled();
        try {
            return ResponseEntity.ok(Map.of("reminders", service.list(includeFired)));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        if (!properties.isEnabled()) return disabled();
        try {
            service.delete(id);
            return ResponseEntity.ok(Map.of("deleted", id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<Map<String, Object>> disabled() {
        return ResponseEntity.status(403).body(Map.of("error", "Reminders skill is disabled"));
    }
}
