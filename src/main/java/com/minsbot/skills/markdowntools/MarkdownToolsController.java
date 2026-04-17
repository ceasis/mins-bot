package com.minsbot.skills.markdowntools;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/markdowntools")
public class MarkdownToolsController {
    private final MarkdownToolsService service;
    private final MarkdownToolsConfig.MarkdownToolsProperties properties;
    public MarkdownToolsController(MarkdownToolsService service, MarkdownToolsConfig.MarkdownToolsProperties properties) { this.service = service; this.properties = properties; }

    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "markdowntools", "enabled", properties.isEnabled())); }

    @PostMapping("/toc") public ResponseEntity<?> toc(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return disabled();
        int maxDepth = body.get("maxDepth") == null ? 6 : ((Number) body.get("maxDepth")).intValue();
        return ResponseEntity.ok(service.toc((String) body.get("markdown"), maxDepth));
    }

    @PostMapping("/links") public ResponseEntity<?> links(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return disabled();
        return ResponseEntity.ok(service.linkReport(body.get("markdown")));
    }

    @PostMapping("/validate-headings") public ResponseEntity<?> validateHeadings(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return disabled();
        return ResponseEntity.ok(service.validateHeadings(body.get("markdown")));
    }

    private ResponseEntity<Map<String, Object>> disabled() { return ResponseEntity.status(403).body(Map.of("error", "MarkdownTools skill is disabled")); }
}
