package com.minsbot.skills.batterystatus;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/batterystatus")
public class BatteryStatusController {
    private final BatteryStatusService svc;
    private final BatteryStatusConfig.BatteryStatusProperties props;
    public BatteryStatusController(BatteryStatusService svc, BatteryStatusConfig.BatteryStatusProperties props) { this.svc = svc; this.props = props; }
    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "batterystatus", "enabled", props.isEnabled())); }
    @GetMapping("/get") public ResponseEntity<?> get() {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.get()); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
