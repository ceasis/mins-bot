package com.minsbot.skills.outreachtracker;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/outreachtracker")
public class OutreachTrackerController {

    private final OutreachTrackerService service;
    private final OutreachTrackerConfig.OutreachTrackerProperties props;

    public OutreachTrackerController(OutreachTrackerService service,
                                     OutreachTrackerConfig.OutreachTrackerProperties props) {
        this.service = service;
        this.props = props;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of("skill", "outreachtracker", "enabled", props.isEnabled(),
                "storageDir", props.getStorageDir(),
                "purpose", "Log outreach attempts + reply rate by campaign/channel"));
    }

    @PostMapping("/log")
    public ResponseEntity<?> log(@RequestBody Map<String, Object> body) {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "outreachtracker skill is disabled"));
        try {
            return ResponseEntity.ok(service.log(
                    (String) body.get("recipient"),
                    (String) body.get("channel"),
                    (String) body.get("subject"),
                    (String) body.get("snippet"),
                    (String) body.get("campaign")));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/replied/{id}")
    public ResponseEntity<?> replied(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "outreachtracker skill is disabled"));
        try {
            String reply = body == null ? null : (String) body.get("reply");
            return ResponseEntity.ok(service.markReplied(id, reply));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/list")
    public ResponseEntity<?> list(@RequestParam(required = false) String campaign) {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "outreachtracker skill is disabled"));
        try { return ResponseEntity.ok(Map.of("records", service.list(campaign))); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    @GetMapping("/stats")
    public ResponseEntity<?> stats(@RequestParam(required = false) String campaign) {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "outreachtracker skill is disabled"));
        try { return ResponseEntity.ok(service.stats(campaign)); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
