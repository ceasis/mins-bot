package com.minsbot.skills.cvelookup;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/cvelookup")
public class CveLookupController {

    private final CveLookupService service;
    private final CveLookupConfig.CveLookupProperties properties;

    public CveLookupController(CveLookupService service,
                               CveLookupConfig.CveLookupProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "cvelookup",
                "enabled", properties.isEnabled(),
                "timeoutMs", properties.getTimeoutMs(),
                "apiBase", properties.getApiBase(),
                "apiKeyConfigured", properties.getApiKey() != null && !properties.getApiKey().isBlank()
        ));
    }

    @GetMapping("/id")
    public ResponseEntity<?> byId(@RequestParam String cve) {
        if (!properties.isEnabled()) return disabled();
        try {
            return ResponseEntity.ok(service.lookup(cve));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam String keyword,
                                    @RequestParam(defaultValue = "20") int limit) {
        if (!properties.isEnabled()) return disabled();
        try {
            return ResponseEntity.ok(service.search(keyword, limit));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<Map<String, Object>> disabled() {
        return ResponseEntity.status(403).body(Map.of("error", "CveLookup skill is disabled"));
    }
}
