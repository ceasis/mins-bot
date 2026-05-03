package com.minsbot.skills.firewallctl;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/firewallctl")
public class FirewallCtlController {
    private final FirewallCtlService svc;
    private final FirewallCtlConfig.FirewallCtlProperties props;
    public FirewallCtlController(FirewallCtlService svc, FirewallCtlConfig.FirewallCtlProperties props) { this.svc = svc; this.props = props; }

    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "firewallctl", "enabled", props.isEnabled())); }

    @GetMapping("/list") public ResponseEntity<?> list(@RequestParam(required = false) String filter) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.listRules(filter)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    @PostMapping("/block-port") public ResponseEntity<?> block(@RequestParam int port, @RequestParam(defaultValue = "in") String direction, @RequestParam(required = false) String name) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.blockPort(port, direction, name)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    @DeleteMapping("/rule") public ResponseEntity<?> del(@RequestParam String name) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.deleteRule(name)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
