package com.minsbot.skills.gpustatus;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/gpustatus")
public class GpuStatusController {
    private final GpuStatusService svc;
    private final GpuStatusConfig.GpuStatusProperties props;
    public GpuStatusController(GpuStatusService svc, GpuStatusConfig.GpuStatusProperties props) { this.svc = svc; this.props = props; }
    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "gpustatus", "enabled", props.isEnabled())); }
    @GetMapping("/get") public ResponseEntity<?> get() {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        return ResponseEntity.ok(svc.get());
    }
}
