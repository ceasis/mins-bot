package com.minsbot.skills.logtail;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/logtail")
public class LogTailController {
    private final LogTailService svc;
    private final LogTailConfig.LogTailProperties props;
    public LogTailController(LogTailService svc, LogTailConfig.LogTailProperties props) { this.svc = svc; this.props = props; }
    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "logtail", "enabled", props.isEnabled())); }
    @GetMapping("/tail") public ResponseEntity<?> tail(@RequestParam String path,
                                                        @RequestParam(defaultValue = "100") int lines,
                                                        @RequestParam(required = false) String filter) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.tail(path, lines, filter)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
