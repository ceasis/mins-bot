package com.minsbot.skills.bigfilescan;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/bigfilescan")
public class BigFileScanController {
    private final BigFileScanService svc;
    private final BigFileScanConfig.BigFileScanProperties props;
    public BigFileScanController(BigFileScanService svc, BigFileScanConfig.BigFileScanProperties props) { this.svc = svc; this.props = props; }
    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "bigfilescan", "enabled", props.isEnabled())); }
    @GetMapping("/scan") public ResponseEntity<?> scan(@RequestParam String path, @RequestParam(required = false) Long minBytes, @RequestParam(required = false) Integer limit) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.scan(path, minBytes, limit)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
