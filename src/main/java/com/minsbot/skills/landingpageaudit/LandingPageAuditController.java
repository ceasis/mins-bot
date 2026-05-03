package com.minsbot.skills.landingpageaudit;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/landingpageaudit")
public class LandingPageAuditController {

    private final LandingPageAuditService service;
    private final LandingPageAuditConfig.LandingPageAuditProperties props;

    public LandingPageAuditController(LandingPageAuditService service,
                                      LandingPageAuditConfig.LandingPageAuditProperties props) {
        this.service = service;
        this.props = props;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of("skill", "landingpageaudit", "enabled", props.isEnabled(),
                "purpose", "Score landing page conversion-readiness with prioritized fixes"));
    }

    @GetMapping("/run")
    public ResponseEntity<?> run(@RequestParam String url) {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "landingpageaudit skill is disabled"));
        try { return ResponseEntity.ok(service.audit(url)); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
