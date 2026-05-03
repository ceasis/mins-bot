package com.minsbot.skills.reviewmonitor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/reviewmonitor")
public class ReviewMonitorController {

    private final ReviewMonitorService service;
    private final ReviewMonitorConfig.ReviewMonitorProperties props;

    public ReviewMonitorController(ReviewMonitorService service,
                                   ReviewMonitorConfig.ReviewMonitorProperties props) {
        this.service = service;
        this.props = props;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of("skill", "reviewmonitor", "enabled", props.isEnabled(),
                "purpose", "Scan review feeds, classify sentiment, surface complaint themes + reply templates"));
    }

    @PostMapping("/run")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> run(@RequestBody Map<String, Object> body) {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "reviewmonitor skill is disabled"));
        try {
            List<String> sources = (List<String>) body.getOrDefault("sources", List.of());
            return ResponseEntity.ok(service.scan(sources));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
