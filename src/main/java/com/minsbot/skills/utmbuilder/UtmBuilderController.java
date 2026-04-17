package com.minsbot.skills.utmbuilder;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/utmbuilder")
public class UtmBuilderController {

    private final UtmBuilderService service;
    private final UtmBuilderConfig.UtmBuilderProperties properties;

    public UtmBuilderController(UtmBuilderService service,
                                UtmBuilderConfig.UtmBuilderProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "utmbuilder",
                "enabled", properties.isEnabled()
        ));
    }

    @PostMapping("/build")
    public ResponseEntity<?> build(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return disabled();
        try {
            String url = body.get("url");
            Map<String, String> utms = new LinkedHashMap<>();
            for (String k : new String[]{"utm_source","utm_medium","utm_campaign","utm_term","utm_content","utm_id"}) {
                if (body.get(k) != null) utms.put(k, body.get(k));
            }
            return ResponseEntity.ok(service.build(url, utms));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/parse")
    public ResponseEntity<?> parse(@RequestParam String url) {
        if (!properties.isEnabled()) return disabled();
        try {
            return ResponseEntity.ok(service.parse(url));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<Map<String, Object>> disabled() {
        return ResponseEntity.status(403).body(Map.of("error", "UtmBuilder skill is disabled"));
    }
}
