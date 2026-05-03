package com.minsbot.skills.archiver;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/archiver")
public class ArchiverController {
    private final ArchiverService svc;
    private final ArchiverConfig.ArchiverProperties props;
    public ArchiverController(ArchiverService svc, ArchiverConfig.ArchiverProperties props) { this.svc = svc; this.props = props; }
    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "archiver", "enabled", props.isEnabled())); }
    @PostMapping("/zip") public ResponseEntity<?> zip(@RequestParam String source, @RequestParam String dest) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.zip(source, dest)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
    @PostMapping("/unzip") public ResponseEntity<?> unzip(@RequestParam String zip, @RequestParam String dest) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.unzip(zip, dest)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
    @GetMapping("/list") public ResponseEntity<?> list(@RequestParam String zip) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.listEntries(zip)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
