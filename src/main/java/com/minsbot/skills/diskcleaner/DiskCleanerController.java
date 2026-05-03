package com.minsbot.skills.diskcleaner;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/diskcleaner")
public class DiskCleanerController {
    private final DiskCleanerService svc;
    private final DiskCleanerConfig.DiskCleanerProperties props;
    public DiskCleanerController(DiskCleanerService svc, DiskCleanerConfig.DiskCleanerProperties props) { this.svc = svc; this.props = props; }
    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "diskcleaner", "enabled", props.isEnabled())); }
    @GetMapping("/temp-dirs") public ResponseEntity<?> tmp() {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        return ResponseEntity.ok(svc.tempDirs());
    }
    @GetMapping("/big-files") public ResponseEntity<?> big(@RequestParam String path, @RequestParam(defaultValue = "30") int limit) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.bigFiles(path, limit)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
    @DeleteMapping("/delete") public ResponseEntity<?> del(@RequestParam String path, @RequestParam(defaultValue = "false") boolean recursive) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.deletePath(path, recursive)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
