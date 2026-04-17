package com.minsbot.skills.markdownhtml;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/markdownhtml")
public class MarkdownHtmlController {
    private final MarkdownHtmlService service;
    private final MarkdownHtmlConfig.MarkdownHtmlProperties properties;
    public MarkdownHtmlController(MarkdownHtmlService service, MarkdownHtmlConfig.MarkdownHtmlProperties properties) { this.service = service; this.properties = properties; }

    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "markdownhtml", "enabled", properties.isEnabled())); }

    @PostMapping("/md-to-html") public ResponseEntity<?> toHtml(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "MarkdownHtml skill is disabled"));
        return ResponseEntity.ok(Map.of("html", service.mdToHtml(body.get("markdown"))));
    }

    @PostMapping("/html-to-md") public ResponseEntity<?> toMd(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "MarkdownHtml skill is disabled"));
        return ResponseEntity.ok(Map.of("markdown", service.htmlToMd(body.get("html"))));
    }
}
