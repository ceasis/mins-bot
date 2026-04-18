package com.minsbot.skills.recurringtask;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/recurringtask")
public class RecurringTaskController {

    private final RecurringTaskService service;

    public RecurringTaskController(RecurringTaskService service) {
        this.service = service;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "recurringtask",
                "taskCount", service.list().size()
        ));
    }

    @GetMapping("/list")
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(Map.of("tasks", service.list()));
    }

    @PostMapping("/create")
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        try {
            String cron = (String) body.get("cron");
            String time = (String) body.get("time");
            String prompt = (String) body.get("prompt");
            String label = (String) body.get("label");
            if ((cron == null || cron.isBlank()) && time != null && !time.isBlank()) {
                cron = RecurringTaskService.dailyCronFromTime(time);
            }
            String id = service.create(cron, prompt, label);
            return ResponseEntity.ok(Map.of("id", id));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        return ResponseEntity.ok(Map.of("deleted", service.delete(id)));
    }

    @PostMapping("/{id}/enabled")
    public ResponseEntity<?> toggle(@PathVariable String id, @RequestBody Map<String, Object> body) {
        try {
            boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
            return ResponseEntity.ok(Map.of("updated", service.setEnabled(id, enabled)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
