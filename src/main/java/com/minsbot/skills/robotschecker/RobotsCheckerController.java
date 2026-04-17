package com.minsbot.skills.robotschecker;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/robotschecker")
public class RobotsCheckerController {

    private final RobotsCheckerService service;
    private final RobotsCheckerConfig.RobotsCheckerProperties properties;

    public RobotsCheckerController(RobotsCheckerService service,
                                   RobotsCheckerConfig.RobotsCheckerProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "robotschecker",
                "enabled", properties.isEnabled(),
                "timeoutMs", properties.getTimeoutMs()
        ));
    }

    @GetMapping("/parse")
    public ResponseEntity<?> parse(@RequestParam String url) {
        if (!properties.isEnabled()) return disabled();
        try {
            return ResponseEntity.ok(service.parse(url));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/check")
    public ResponseEntity<?> check(@RequestParam String url,
                                   @RequestParam String path,
                                   @RequestParam(defaultValue = "*") String userAgent) {
        if (!properties.isEnabled()) return disabled();
        try {
            return ResponseEntity.ok(service.check(url, path, userAgent));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<Map<String, Object>> disabled() {
        return ResponseEntity.status(403).body(Map.of("error", "RobotsChecker skill is disabled"));
    }
}
