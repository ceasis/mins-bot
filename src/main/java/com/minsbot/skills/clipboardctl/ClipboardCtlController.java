package com.minsbot.skills.clipboardctl;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/clipboardctl")
public class ClipboardCtlController {
    private final ClipboardCtlService svc;
    private final ClipboardCtlConfig.ClipboardCtlProperties props;
    public ClipboardCtlController(ClipboardCtlService svc, ClipboardCtlConfig.ClipboardCtlProperties props) { this.svc = svc; this.props = props; }
    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "clipboardctl", "enabled", props.isEnabled())); }
    @GetMapping("/read") public ResponseEntity<?> read() {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.read()); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
    @PostMapping("/write") public ResponseEntity<?> write(@RequestBody Map<String, Object> body) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.write((String) body.getOrDefault("text", ""))); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
    @PostMapping("/clear") public ResponseEntity<?> clear() {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        return ResponseEntity.ok(svc.clear());
    }
}
