package com.minsbot.skills.emailcampaign;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/emailcampaign")
public class EmailCampaignController {

    private final EmailCampaignService service;
    private final EmailCampaignConfig.EmailCampaignProperties props;

    public EmailCampaignController(EmailCampaignService service,
                                   EmailCampaignConfig.EmailCampaignProperties props) {
        this.service = service;
        this.props = props;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "emailcampaign",
                "enabled", props.isEnabled(),
                "purpose", "Audit email subject + body for deliverability and spam triggers"
        ));
    }

    @PostMapping("/audit")
    public ResponseEntity<?> audit(@RequestBody Map<String, Object> body) {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "emailcampaign skill is disabled"));
        try {
            String subject = (String) body.get("subject");
            String preheader = (String) body.get("preheader");
            String emailBody = (String) body.get("body");
            return ResponseEntity.ok(service.audit(subject, preheader, emailBody));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
