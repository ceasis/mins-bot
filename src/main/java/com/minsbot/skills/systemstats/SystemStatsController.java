package com.minsbot.skills.systemstats;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/systemstats")
public class SystemStatsController {
    private final SystemStatsService svc;
    private final SystemStatsConfig.SystemStatsProperties props;
    public SystemStatsController(SystemStatsService svc, SystemStatsConfig.SystemStatsProperties props) { this.svc = svc; this.props = props; }
    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "systemstats", "enabled", props.isEnabled())); }
    @GetMapping("/snapshot") public ResponseEntity<?> snap() {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        return ResponseEntity.ok(svc.snapshot());
    }
}
