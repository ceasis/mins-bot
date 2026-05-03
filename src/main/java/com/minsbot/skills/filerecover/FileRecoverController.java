package com.minsbot.skills.filerecover;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/filerecover")
public class FileRecoverController {
    private final FileRecoverService svc;
    private final FileRecoverConfig.FileRecoverProperties props;
    public FileRecoverController(FileRecoverService svc, FileRecoverConfig.FileRecoverProperties props) { this.svc = svc; this.props = props; }
    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "filerecover", "enabled", props.isEnabled())); }
    @GetMapping("/list") public ResponseEntity<?> list() {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(Map.of("trash", svc.list())); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
    @PostMapping("/restore") public ResponseEntity<?> restore(@RequestParam String name) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.restore(name)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
