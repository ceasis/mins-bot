package com.minsbot.skills.upcoming;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/upcoming")
public class UpcomingController {

    private final UpcomingService service;

    public UpcomingController(UpcomingService service) {
        this.service = service;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "upcoming",
                "enabled", service.getProperties().isEnabled(),
                "defaultDays", service.getProperties().getDefaultDays(),
                "maxDays", service.getProperties().getMaxDays()
        ));
    }

    @GetMapping("/digest")
    public ResponseEntity<?> digest(@RequestParam(required = false) Integer days) {
        if (!service.getProperties().isEnabled()) {
            return ResponseEntity.status(403).body(Map.of("error", "Upcoming skill is disabled"));
        }
        int n = days != null ? days : service.getProperties().getDefaultDays();
        return ResponseEntity.ok(Map.of(
                "days", n,
                "digest", service.buildDigest(n)
        ));
    }
}
