package com.minsbot.skills.vpncheck;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/vpncheck")
public class VpnCheckController {
    private final VpnCheckService svc;
    private final VpnCheckConfig.VpnCheckProperties props;
    public VpnCheckController(VpnCheckService svc, VpnCheckConfig.VpnCheckProperties props) { this.svc = svc; this.props = props; }

    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "vpncheck", "enabled", props.isEnabled())); }

    @GetMapping("/check") public ResponseEntity<?> check() {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.check()); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
