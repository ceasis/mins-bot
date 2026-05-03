package com.minsbot.skills.windowctl;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/windowctl")
public class WindowCtlController {
    private final WindowCtlService svc;
    private final WindowCtlConfig.WindowCtlProperties props;
    public WindowCtlController(WindowCtlService svc, WindowCtlConfig.WindowCtlProperties props) { this.svc = svc; this.props = props; }
    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "windowctl", "enabled", props.isEnabled())); }
    @GetMapping("/list") public ResponseEntity<?> list() {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(Map.of("windows", svc.list())); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
    @PostMapping("/focus") public ResponseEntity<?> focus(@RequestParam String title) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.focus(title)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
