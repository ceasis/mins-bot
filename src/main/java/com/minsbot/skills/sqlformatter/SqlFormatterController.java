package com.minsbot.skills.sqlformatter;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/sqlformatter")
public class SqlFormatterController {
    private final SqlFormatterService service;
    private final SqlFormatterConfig.SqlFormatterProperties properties;
    public SqlFormatterController(SqlFormatterService service, SqlFormatterConfig.SqlFormatterProperties properties) { this.service = service; this.properties = properties; }

    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "sqlformatter", "enabled", properties.isEnabled())); }

    @PostMapping("/format") public ResponseEntity<?> format(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return disabled();
        return ResponseEntity.ok(Map.of("sql", service.format(body.get("sql"))));
    }

    @PostMapping("/minify") public ResponseEntity<?> minify(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return disabled();
        return ResponseEntity.ok(Map.of("sql", service.minify(body.get("sql"))));
    }

    private ResponseEntity<Map<String, Object>> disabled() { return ResponseEntity.status(403).body(Map.of("error", "SqlFormatter skill is disabled")); }
}
