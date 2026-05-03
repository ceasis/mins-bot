package com.minsbot.skills.servicectl;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/servicectl")
public class ServiceCtlController {
    private final ServiceCtlService svc;
    private final ServiceCtlConfig.ServiceCtlProperties props;
    public ServiceCtlController(ServiceCtlService svc, ServiceCtlConfig.ServiceCtlProperties props) { this.svc = svc; this.props = props; }

    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "servicectl", "enabled", props.isEnabled())); }

    @GetMapping("/list") public ResponseEntity<?> list(@RequestParam(required = false) String filter) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.list(filter)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    @PostMapping("/{op}") public ResponseEntity<?> action(@PathVariable String op, @RequestParam String name) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.action(name, op)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
