package com.minsbot.skills.emailsender;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/emailsender")
public class EmailSenderController {

    private final EmailSenderService service;
    private final EmailSenderConfig.EmailSenderProperties props;

    public EmailSenderController(EmailSenderService service, EmailSenderConfig.EmailSenderProperties props) {
        this.service = service;
        this.props = props;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of("skill", "emailsender", "enabled", props.isEnabled(),
                "resendConfigured", !props.getResendApiKey().isBlank(),
                "smtpConfigured", !props.getSmtpHost().isBlank(),
                "dailyMaxSends", props.getDailyMaxSends(),
                "purpose", "Send emails via Resend or SMTP, auto-log to outreachtracker"));
    }

    @PostMapping("/send")
    public ResponseEntity<?> send(@RequestBody Map<String, Object> body) {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "emailsender skill is disabled"));
        try {
            return ResponseEntity.ok(service.send(
                    (String) body.get("to"),
                    (String) body.get("subject"),
                    (String) body.get("body"),
                    (String) body.get("html"),
                    (String) body.get("campaign")));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/stats")
    public ResponseEntity<?> stats() {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "emailsender skill is disabled"));
        return ResponseEntity.ok(service.stats());
    }
}
