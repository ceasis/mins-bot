package com.minsbot.skills.fileinspect;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/fileinspect")
public class FileInspectController {
    private final FileInspectService svc;
    private final FileInspectConfig.FileInspectProperties props;
    public FileInspectController(FileInspectService svc, FileInspectConfig.FileInspectProperties props) { this.svc = svc; this.props = props; }
    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "fileinspect", "enabled", props.isEnabled())); }
    @GetMapping("/head") public ResponseEntity<?> head(@RequestParam String path, @RequestParam(defaultValue = "20") int n) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.head(path, n)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
    @GetMapping("/tail") public ResponseEntity<?> tail(@RequestParam String path, @RequestParam(defaultValue = "20") int n) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.tail(path, n)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
    @GetMapping("/wc") public ResponseEntity<?> wc(@RequestParam String path) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.wc(path)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
