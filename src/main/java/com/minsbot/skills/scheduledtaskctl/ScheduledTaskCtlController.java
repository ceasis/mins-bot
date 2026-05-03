package com.minsbot.skills.scheduledtaskctl;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/scheduledtaskctl")
public class ScheduledTaskCtlController {
    private final ScheduledTaskCtlService svc;
    private final ScheduledTaskCtlConfig.ScheduledTaskCtlProperties props;
    public ScheduledTaskCtlController(ScheduledTaskCtlService svc, ScheduledTaskCtlConfig.ScheduledTaskCtlProperties props) { this.svc = svc; this.props = props; }
    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "scheduledtaskctl", "enabled", props.isEnabled())); }
    @GetMapping("/list") public ResponseEntity<?> list(@RequestParam(required = false) String filter) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.list(filter)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
    @PostMapping("/create") public ResponseEntity<?> create(@RequestParam String name, @RequestParam String command, @RequestParam String schedule) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.create(name, command, schedule)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
    @DeleteMapping("/delete") public ResponseEntity<?> del(@RequestParam String name) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.delete(name)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
