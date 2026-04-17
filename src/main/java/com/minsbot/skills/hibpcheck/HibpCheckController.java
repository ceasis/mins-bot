package com.minsbot.skills.hibpcheck;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/hibpcheck")
public class HibpCheckController {

    private final HibpCheckService service;
    private final HibpCheckConfig.HibpCheckProperties properties;

    public HibpCheckController(HibpCheckService service,
                               HibpCheckConfig.HibpCheckProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "hibpcheck",
                "enabled", properties.isEnabled(),
                "timeoutMs", properties.getTimeoutMs(),
                "apiBase", properties.getApiBase(),
                "method", "k-anonymity (SHA1 prefix only sent over HTTPS)"
        ));
    }

    @PostMapping("/check")
    public ResponseEntity<?> check(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return disabled();
        String password = body.get("password");
        if (password == null) return ResponseEntity.badRequest().body(Map.of("error", "password required"));
        try {
            return ResponseEntity.ok(service.check(password));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<Map<String, Object>> disabled() {
        return ResponseEntity.status(403).body(Map.of("error", "HibpCheck skill is disabled"));
    }
}
