package com.minsbot.skills.backlinkfinder;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/backlinkfinder")
public class BacklinkFinderController {

    private final BacklinkFinderService service;
    private final BacklinkFinderConfig.BacklinkFinderProperties props;

    public BacklinkFinderController(BacklinkFinderService service,
                                    BacklinkFinderConfig.BacklinkFinderProperties props) {
        this.service = service;
        this.props = props;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of("skill", "backlinkfinder", "enabled", props.isEnabled(),
                "purpose", "Find domains competitors cite that your site doesn't"));
    }

    @PostMapping("/run")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> run(@RequestBody Map<String, Object> body) {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "backlinkfinder skill is disabled"));
        try {
            String yourSite = (String) body.get("yourSite");
            List<String> competitors = (List<String>) body.getOrDefault("competitorSites", List.of());
            return ResponseEntity.ok(service.find(yourSite, competitors));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
