package com.minsbot.skills.buildwatcher;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/buildwatcher")
public class BuildWatcherController {
    private final BuildWatcherService svc;
    private final BuildWatcherConfig.BuildWatcherProperties props;
    public BuildWatcherController(BuildWatcherService svc, BuildWatcherConfig.BuildWatcherProperties props) { this.svc = svc; this.props = props; }
    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "buildwatcher", "enabled", props.isEnabled())); }
    @GetMapping("/detect") public ResponseEntity<?> detect(@RequestParam(required = false) String path) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        return ResponseEntity.ok(svc.detect(path));
    }
    @PostMapping("/build") public ResponseEntity<?> build(@RequestParam(required = false) String path) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.build(path)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
