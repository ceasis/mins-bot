package com.minsbot.skills.passwordstrength;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/passwordstrength")
public class PasswordStrengthController {

    private final PasswordStrengthService service;
    private final PasswordStrengthConfig.PasswordStrengthProperties properties;

    public PasswordStrengthController(PasswordStrengthService service,
                                      PasswordStrengthConfig.PasswordStrengthProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "passwordstrength",
                "enabled", properties.isEnabled(),
                "maxLength", properties.getMaxLength()
        ));
    }

    @PostMapping("/evaluate")
    public ResponseEntity<?> evaluate(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return disabled();
        String password = body.get("password");
        if (password == null) return ResponseEntity.badRequest().body(Map.of("error", "password required"));
        if (password.length() > properties.getMaxLength()) {
            return ResponseEntity.badRequest().body(Map.of("error", "password exceeds maxLength"));
        }
        return ResponseEntity.ok(service.evaluate(password));
    }

    private ResponseEntity<Map<String, Object>> disabled() {
        return ResponseEntity.status(403).body(Map.of("error", "PasswordStrength skill is disabled"));
    }
}
