package com.minsbot.skills.emailvalidator;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/emailvalidator")
public class EmailValidatorController {

    private final EmailValidatorService service;
    private final EmailValidatorConfig.EmailValidatorProperties properties;

    public EmailValidatorController(EmailValidatorService service,
                                    EmailValidatorConfig.EmailValidatorProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "emailvalidator",
                "enabled", properties.isEnabled(),
                "checkMx", properties.isCheckMx(),
                "timeoutMs", properties.getTimeoutMs(),
                "disposableDomainCount", properties.getDisposableDomains().size()
        ));
    }

    @GetMapping("/validate")
    public ResponseEntity<?> validate(@RequestParam String email) {
        if (!properties.isEnabled()) return disabled();
        return ResponseEntity.ok(service.validate(email));
    }

    private ResponseEntity<Map<String, Object>> disabled() {
        return ResponseEntity.status(403).body(Map.of("error", "EmailValidator skill is disabled"));
    }
}
