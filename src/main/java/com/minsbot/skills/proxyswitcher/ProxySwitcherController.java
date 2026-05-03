package com.minsbot.skills.proxyswitcher;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/proxyswitcher")
public class ProxySwitcherController {
    private final ProxySwitcherService svc;
    private final ProxySwitcherConfig.ProxySwitcherProperties props;
    public ProxySwitcherController(ProxySwitcherService svc, ProxySwitcherConfig.ProxySwitcherProperties props) { this.svc = svc; this.props = props; }

    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "proxyswitcher", "enabled", props.isEnabled(), "presets", props.getPresets())); }

    @GetMapping("/get") public ResponseEntity<?> get() {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.get()); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
    @PostMapping("/set") public ResponseEntity<?> set(@RequestParam String hostPort) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.set(hostPort)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
    @PostMapping("/off") public ResponseEntity<?> off() {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.off()); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
    @PostMapping("/preset") public ResponseEntity<?> preset(@RequestParam String name) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.usePreset(name)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
