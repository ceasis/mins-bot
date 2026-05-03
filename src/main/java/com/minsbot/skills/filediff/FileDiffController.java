package com.minsbot.skills.filediff;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/filediff")
public class FileDiffController {
    private final FileDiffService svc;
    private final FileDiffConfig.FileDiffProperties props;
    public FileDiffController(FileDiffService svc, FileDiffConfig.FileDiffProperties props) { this.svc = svc; this.props = props; }
    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "filediff", "enabled", props.isEnabled())); }
    @GetMapping("/files") public ResponseEntity<?> files(@RequestParam String a, @RequestParam String b) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.diffFiles(a, b)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
    @GetMapping("/folders") public ResponseEntity<?> folders(@RequestParam String a, @RequestParam String b) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.diffFolders(a, b)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
