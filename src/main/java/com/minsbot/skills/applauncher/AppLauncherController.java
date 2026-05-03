package com.minsbot.skills.applauncher;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/applauncher")
public class AppLauncherController {
    private final AppLauncherService svc;
    private final AppLauncherConfig.AppLauncherProperties props;
    public AppLauncherController(AppLauncherService svc, AppLauncherConfig.AppLauncherProperties props) { this.svc = svc; this.props = props; }
    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "applauncher", "enabled", props.isEnabled(), "registry", props.getRegistry())); }
    @PostMapping("/launch") public ResponseEntity<?> launch(@RequestParam String target) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.launch(target)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
