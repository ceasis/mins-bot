package com.minsbot.skills.selfmarket;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/selfmarket")
public class SelfMarketController {

    private final SelfMarketService service;
    private final SelfMarketConfig.SelfMarketProperties props;

    public SelfMarketController(SelfMarketService service, SelfMarketConfig.SelfMarketProperties props) {
        this.service = service;
        this.props = props;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of("skill", "selfmarket", "enabled", props.isEnabled(),
                "product", props.getProduct(), "landingPage", props.getLandingPage(),
                "purpose", "Orchestrate trends → competitor → adcopy → social → audit → outreach into a daily playbook"));
    }

    @PostMapping("/run")
    public ResponseEntity<?> run(@RequestBody(required = false) Map<String, Object> body) {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "selfmarket skill is disabled"));
        try { return ResponseEntity.ok(service.run(body == null ? Map.of() : body)); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
