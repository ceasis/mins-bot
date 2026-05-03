package com.minsbot.skills.portmap;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/portmap")
public class PortMapController {
    private final PortMapService svc;
    private final PortMapConfig.PortMapProperties props;
    public PortMapController(PortMapService svc, PortMapConfig.PortMapProperties props) { this.svc = svc; this.props = props; }

    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "portmap", "enabled", props.isEnabled())); }

    @GetMapping("/list") public ResponseEntity<?> list() {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.listAll()); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
