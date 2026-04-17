package com.minsbot.skills.imagemeta;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/imagemeta")
public class ImageMetaController {
    private final ImageMetaService service;
    private final ImageMetaConfig.ImageMetaProperties properties;

    public ImageMetaController(ImageMetaService service, ImageMetaConfig.ImageMetaProperties properties) {
        this.service = service; this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of("skill", "imagemeta", "enabled", properties.isEnabled(),
                "maxFileBytes", properties.getMaxFileBytes()));
    }

    @GetMapping("/inspect")
    public ResponseEntity<?> inspect(@RequestParam String path) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.inspect(path, properties.getMaxFileBytes())); }
        catch (IllegalArgumentException e) { return bad(e); }
        catch (Exception e) { return ResponseEntity.status(500).body(Map.of("error", e.getMessage())); }
    }

    @GetMapping("/compare")
    public ResponseEntity<?> compare(@RequestParam String a, @RequestParam String b) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.compareDimensions(a, b, properties.getMaxFileBytes())); }
        catch (IllegalArgumentException e) { return bad(e); }
        catch (Exception e) { return ResponseEntity.status(500).body(Map.of("error", e.getMessage())); }
    }

    private ResponseEntity<Map<String, Object>> disabled() { return ResponseEntity.status(403).body(Map.of("error", "ImageMeta skill is disabled")); }
    private ResponseEntity<Map<String, Object>> bad(Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
}
