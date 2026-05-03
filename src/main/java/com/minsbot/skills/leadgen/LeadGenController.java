package com.minsbot.skills.leadgen;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/leadgen")
public class LeadGenController {

    private final LeadGenService service;
    private final LeadGenConfig.LeadGenProperties props;

    public LeadGenController(LeadGenService service, LeadGenConfig.LeadGenProperties props) {
        this.service = service;
        this.props = props;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "leadgen",
                "enabled", props.isEnabled(),
                "maxSources", props.getMaxSources(),
                "maxResults", props.getMaxResults(),
                "purpose", "Find buying-intent leads with detected contact info"
        ));
    }

    @PostMapping("/run")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> run(@RequestBody Map<String, Object> body) {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "leadgen skill is disabled"));
        try {
            List<String> services = (List<String>) body.getOrDefault("serviceKeywords", List.of());
            List<String> sources = (List<String>) body.getOrDefault("sources", List.of());
            Integer max = body.get("maxResults") instanceof Number n ? n.intValue() : null;
            return ResponseEntity.ok(service.run(services, sources, max));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
