package com.minsbot.skills.certinspector;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/certinspector")
public class CertInspectorController {

    private final CertInspectorService service;
    private final CertInspectorConfig.CertInspectorProperties properties;

    public CertInspectorController(CertInspectorService service,
                                   CertInspectorConfig.CertInspectorProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "certinspector",
                "enabled", properties.isEnabled(),
                "timeoutMs", properties.getTimeoutMs()
        ));
    }

    @GetMapping("/inspect")
    public ResponseEntity<?> inspect(@RequestParam String host,
                                     @RequestParam(defaultValue = "443") int port) {
        if (!properties.isEnabled()) return disabled();
        try {
            return ResponseEntity.ok(service.inspect(host, port));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<Map<String, Object>> disabled() {
        return ResponseEntity.status(403).body(Map.of("error", "CertInspector skill is disabled"));
    }
}
