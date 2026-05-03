package com.minsbot.skills.duplicatefinder;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/duplicatefinder")
public class DuplicateFinderController {
    private final DuplicateFinderService svc;
    private final DuplicateFinderConfig.DuplicateFinderProperties props;
    public DuplicateFinderController(DuplicateFinderService svc, DuplicateFinderConfig.DuplicateFinderProperties props) { this.svc = svc; this.props = props; }
    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "duplicatefinder", "enabled", props.isEnabled())); }
    @GetMapping("/find") public ResponseEntity<?> find(@RequestParam String path) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.find(path)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
