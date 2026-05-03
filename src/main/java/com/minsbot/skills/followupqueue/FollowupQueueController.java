package com.minsbot.skills.followupqueue;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/followupqueue")
public class FollowupQueueController {

    private final FollowupQueueService service;
    private final FollowupQueueConfig.FollowupQueueProperties props;

    public FollowupQueueController(FollowupQueueService service,
                                   FollowupQueueConfig.FollowupQueueProperties props) {
        this.service = service;
        this.props = props;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of("skill", "followupqueue", "enabled", props.isEnabled(),
                "storageDir", props.getStorageDir(), "cadenceDays", props.getCadenceDays(),
                "purpose", "Schedule + surface due follow-ups by cadence"));
    }

    @PostMapping("/add")
    public ResponseEntity<?> add(@RequestBody Map<String, Object> body) {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "followupqueue skill is disabled"));
        try {
            return ResponseEntity.ok(service.add(
                    (String) body.get("leadName"),
                    (String) body.get("contact"),
                    (String) body.get("channel"),
                    (String) body.get("firstContacted"),
                    (String) body.get("notes")));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/touch/{id}")
    public ResponseEntity<?> touch(@PathVariable String id) {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "followupqueue skill is disabled"));
        try { return ResponseEntity.ok(service.touch(id)); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    @PostMapping("/close/{id}")
    public ResponseEntity<?> close(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "followupqueue skill is disabled"));
        try {
            String outcome = body == null ? null : (String) body.get("outcome");
            return ResponseEntity.ok(service.close(id, outcome));
        } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    @GetMapping("/due")
    public ResponseEntity<?> due() {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "followupqueue skill is disabled"));
        try { return ResponseEntity.ok(Map.of("due", service.due())); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
