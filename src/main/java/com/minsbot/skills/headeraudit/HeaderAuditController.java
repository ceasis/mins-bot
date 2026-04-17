package com.minsbot.skills.headeraudit;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/headeraudit")
public class HeaderAuditController {

    private final HeaderAuditService service;
    private final HeaderAuditConfig.HeaderAuditProperties properties;

    public HeaderAuditController(HeaderAuditService service,
                                 HeaderAuditConfig.HeaderAuditProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "headeraudit",
                "enabled", properties.isEnabled(),
                "timeoutMs", properties.getTimeoutMs()
        ));
    }

    @GetMapping("/audit")
    public ResponseEntity<?> audit(@RequestParam String url) {
        if (!properties.isEnabled()) return disabled();
        try {
            return ResponseEntity.ok(service.audit(url));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<Map<String, Object>> disabled() {
        return ResponseEntity.status(403).body(Map.of("error", "HeaderAudit skill is disabled"));
    }
}
