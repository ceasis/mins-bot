package com.minsbot.skills.fileopen;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/fileopen")
public class FileOpenController {
    private final FileOpenService svc;
    private final FileOpenConfig.FileOpenProperties props;
    public FileOpenController(FileOpenService svc, FileOpenConfig.FileOpenProperties props) { this.svc = svc; this.props = props; }
    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "fileopen", "enabled", props.isEnabled())); }
    @PostMapping("/open") public ResponseEntity<?> open(@RequestParam String path) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.openFile(path)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
    @PostMapping("/reveal") public ResponseEntity<?> reveal(@RequestParam String path) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.revealInExplorer(path)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
