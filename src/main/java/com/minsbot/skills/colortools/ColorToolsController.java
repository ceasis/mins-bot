package com.minsbot.skills.colortools;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/colortools")
public class ColorToolsController {
    private final ColorToolsService service;
    private final ColorToolsConfig.ColorToolsProperties properties;

    public ColorToolsController(ColorToolsService service, ColorToolsConfig.ColorToolsProperties properties) {
        this.service = service; this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "colortools", "enabled", properties.isEnabled())); }

    @GetMapping("/parse")
    public ResponseEntity<?> parse(@RequestParam String color) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.parse(color)); } catch (IllegalArgumentException e) { return bad(e); }
    }

    @GetMapping("/contrast")
    public ResponseEntity<?> contrast(@RequestParam String fg, @RequestParam String bg) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.contrast(fg, bg)); } catch (IllegalArgumentException e) { return bad(e); }
    }

    @GetMapping("/palette")
    public ResponseEntity<?> palette(@RequestParam String base, @RequestParam(defaultValue = "complementary") String scheme) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.palette(base, scheme)); } catch (IllegalArgumentException e) { return bad(e); }
    }

    private ResponseEntity<Map<String, Object>> disabled() { return ResponseEntity.status(403).body(Map.of("error", "ColorTools skill is disabled")); }
    private ResponseEntity<Map<String, Object>> bad(Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
}
