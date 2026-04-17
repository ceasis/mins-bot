package com.minsbot.skills.csvtools;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/csvtools")
public class CsvToolsController {
    private final CsvToolsService service;
    private final CsvToolsConfig.CsvToolsProperties properties;

    public CsvToolsController(CsvToolsService service, CsvToolsConfig.CsvToolsProperties properties) {
        this.service = service; this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of("skill", "csvtools", "enabled", properties.isEnabled(),
                "maxInputBytes", properties.getMaxInputBytes()));
    }

    @PostMapping("/describe")
    public ResponseEntity<?> describe(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return disabled();
        String csv = body.get("csv"); if (csv == null) return bad("csv required");
        if (csv.length() > properties.getMaxInputBytes()) return bad("exceeds maxInputBytes");
        char delim = body.getOrDefault("delimiter", ",").charAt(0);
        return ResponseEntity.ok(service.describe(csv, delim));
    }

    @PostMapping("/column")
    public ResponseEntity<?> column(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return disabled();
        try {
            char delim = body.getOrDefault("delimiter", ",").charAt(0);
            return ResponseEntity.ok(service.extractColumn(body.get("csv"), body.get("column"), delim));
        } catch (IllegalArgumentException e) { return bad(e.getMessage()); }
    }

    @PostMapping("/filter")
    public ResponseEntity<?> filter(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return disabled();
        try {
            char delim = body.getOrDefault("delimiter", ",").charAt(0);
            return ResponseEntity.ok(service.filter(body.get("csv"), body.get("column"), body.get("contains"), delim));
        } catch (IllegalArgumentException e) { return bad(e.getMessage()); }
    }

    @PostMapping("/to-json")
    public ResponseEntity<?> toJson(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return disabled();
        char delim = body.getOrDefault("delimiter", ",").charAt(0);
        return ResponseEntity.ok(service.toJson(body.get("csv"), delim));
    }

    private ResponseEntity<Map<String, Object>> disabled() { return ResponseEntity.status(403).body(Map.of("error", "CsvTools skill is disabled")); }
    private ResponseEntity<Map<String, Object>> bad(String msg) { return ResponseEntity.badRequest().body(Map.of("error", msg)); }
}
