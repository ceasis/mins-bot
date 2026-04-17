package com.minsbot.skills.difftool;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/difftool")
public class DiffToolController {
    private final DiffToolService service;
    private final DiffToolConfig.DiffToolProperties properties;
    public DiffToolController(DiffToolService service, DiffToolConfig.DiffToolProperties properties) { this.service = service; this.properties = properties; }

    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "difftool", "enabled", properties.isEnabled())); }

    @PostMapping("/lines") public ResponseEntity<?> lines(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return disabled();
        return ResponseEntity.ok(service.lineDiff(body.get("a"), body.get("b")));
    }

    @PostMapping("/unified") public ResponseEntity<?> unified(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return disabled();
        return ResponseEntity.ok(Map.of("diff", service.unifiedDiff(body.get("a"), body.get("b"), body.get("fileA"), body.get("fileB"))));
    }

    @PostMapping("/similarity") public ResponseEntity<?> similarity(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return disabled();
        return ResponseEntity.ok(service.similarity(body.get("a"), body.get("b")));
    }

    private ResponseEntity<Map<String, Object>> disabled() { return ResponseEntity.status(403).body(Map.of("error", "DiffTool skill is disabled")); }
}
