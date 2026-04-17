package com.minsbot.skills.citationformatter;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/citationformatter")
public class CitationFormatterController {
    private final CitationFormatterService service;
    private final CitationFormatterConfig.CitationFormatterProperties properties;

    public CitationFormatterController(CitationFormatterService service, CitationFormatterConfig.CitationFormatterProperties properties) {
        this.service = service; this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of("skill", "citationformatter", "enabled", properties.isEnabled(),
                "styles", service.supportedStyles()));
    }

    @PostMapping("/format")
    public ResponseEntity<?> format(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "CitationFormatter skill is disabled"));
        String style = body.getOrDefault("style", "APA");
        try { return ResponseEntity.ok(service.format(body, style)); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
