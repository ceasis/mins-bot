package com.minsbot.skills.meetingcost;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/meetingcost")
public class MeetingCostController {
    private final MeetingCostService service;
    private final MeetingCostConfig.MeetingCostProperties properties;

    public MeetingCostController(MeetingCostService service, MeetingCostConfig.MeetingCostProperties properties) {
        this.service = service; this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "meetingcost", "enabled", properties.isEnabled())); }

    @GetMapping("/simple")
    public ResponseEntity<?> simple(@RequestParam int attendees, @RequestParam double avgHourlyRate, @RequestParam int durationMinutes) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.computeSimple(attendees, avgHourlyRate, durationMinutes)); } catch (IllegalArgumentException e) { return bad(e); }
    }

    @PostMapping("/detailed")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> detailed(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return disabled();
        try {
            List<Map<String, Object>> attendees = (List<Map<String, Object>>) body.get("attendees");
            int durationMinutes = ((Number) body.get("durationMinutes")).intValue();
            return ResponseEntity.ok(service.computeDetailed(attendees, durationMinutes));
        } catch (IllegalArgumentException e) { return bad(e); } catch (Exception e) { return bad(e); }
    }

    private ResponseEntity<Map<String, Object>> disabled() { return ResponseEntity.status(403).body(Map.of("error", "MeetingCost skill is disabled")); }
    private ResponseEntity<Map<String, Object>> bad(Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
}
