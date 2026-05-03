package com.minsbot.skills.autostartmanager;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/autostartmanager")
public class AutoStartManagerController {
    private final AutoStartManagerService svc;
    private final AutoStartManagerConfig.AutoStartManagerProperties props;
    public AutoStartManagerController(AutoStartManagerService svc, AutoStartManagerConfig.AutoStartManagerProperties props) { this.svc = svc; this.props = props; }
    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "autostartmanager", "enabled", props.isEnabled())); }
    @GetMapping("/list") public ResponseEntity<?> list() {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.list()); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
    @PostMapping("/disable") public ResponseEntity<?> disable(@RequestParam String name) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.disableEntry(name)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    @PostMapping("/add") public ResponseEntity<?> add(@RequestParam String name, @RequestParam String command) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.addEntry(name, command)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    @PostMapping("/install-self") public ResponseEntity<?> installSelf() {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.installSelf()); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    @PostMapping("/uninstall-self") public ResponseEntity<?> uninstallSelf() {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.uninstallSelf()); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
