package com.minsbot.skills.appkill;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/appkill")
public class AppKillController {
    private final AppKillService svc;
    private final AppKillConfig.AppKillProperties props;
    public AppKillController(AppKillService svc, AppKillConfig.AppKillProperties props) { this.svc = svc; this.props = props; }
    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "appkill", "enabled", props.isEnabled(), "protectedNames", props.getProtectedNames())); }
    @PostMapping("/kill") public ResponseEntity<?> kill(@RequestParam String name) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.killByExeName(name)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
