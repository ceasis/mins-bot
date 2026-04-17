package com.minsbot.skills.exifstripper;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/exifstripper")
public class ExifStripperController {
    private final ExifStripperService service;
    private final ExifStripperConfig.ExifStripperProperties properties;
    public ExifStripperController(ExifStripperService service, ExifStripperConfig.ExifStripperProperties properties) { this.service = service; this.properties = properties; }

    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "exifstripper", "enabled", properties.isEnabled())); }

    @PostMapping("/strip") public ResponseEntity<?> strip(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "ExifStripper skill is disabled"));
        try { return ResponseEntity.ok(service.strip(body.get("inputPath"), body.get("outputPath"), properties.getMaxFileBytes())); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
        catch (Exception e) { return ResponseEntity.status(500).body(Map.of("error", e.getMessage())); }
    }
}
