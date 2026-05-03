package com.minsbot.skills.dailybriefing;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/dailybriefing")
public class DailyBriefingController {

    private final DailyBriefingService service;
    private final DailyBriefingConfig.DailyBriefingProperties props;

    public DailyBriefingController(DailyBriefingService service,
                                   DailyBriefingConfig.DailyBriefingProperties props) {
        this.service = service;
        this.props = props;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of("skill", "dailybriefing", "enabled", props.isEnabled(),
                "storageDir", props.getStorageDir(),
                "purpose", "Aggregate gighunter+leadgen+contentresearch+marketresearch into a daily digest"));
    }

    @PostMapping("/run")
    public ResponseEntity<?> run(@RequestBody Map<String, Object> body) {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "dailybriefing skill is disabled"));
        try { return ResponseEntity.ok(service.compile(body)); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    @GetMapping("/list")
    public ResponseEntity<?> list() {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "dailybriefing skill is disabled"));
        try { return ResponseEntity.ok(Map.of("briefings", service.listBriefings())); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
