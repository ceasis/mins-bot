package com.minsbot.skills.okrtracker;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/okrtracker")
public class OkrTrackerController {
    private final OkrTrackerService service;
    private final OkrTrackerConfig.OkrTrackerProperties properties;

    public OkrTrackerController(OkrTrackerService service, OkrTrackerConfig.OkrTrackerProperties properties) {
        this.service = service; this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of("skill", "okrtracker", "enabled", properties.isEnabled(),
                "storageDir", properties.getStorageDir()));
    }

    @PostMapping
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return disabled();
        try {
            String obj = (String) body.get("objective");
            List<Map<String, Object>> krs = (List<Map<String, Object>>) body.get("keyResults");
            String owner = (String) body.get("owner");
            return ResponseEntity.ok(service.create(obj, krs, owner));
        } catch (IllegalArgumentException e) { return bad(e); } catch (Exception e) { return err(e); }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.get(id)); }
        catch (IllegalArgumentException e) { return ResponseEntity.status(404).body(Map.of("error", e.getMessage())); }
        catch (Exception e) { return err(e); }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(service.update(id, body)); }
        catch (IllegalArgumentException e) { return ResponseEntity.status(404).body(Map.of("error", e.getMessage())); }
        catch (Exception e) { return err(e); }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        if (!properties.isEnabled()) return disabled();
        try { service.delete(id); return ResponseEntity.ok(Map.of("deleted", id)); }
        catch (IllegalArgumentException e) { return ResponseEntity.status(404).body(Map.of("error", e.getMessage())); }
        catch (Exception e) { return err(e); }
    }

    @GetMapping
    public ResponseEntity<?> list() {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(Map.of("okrs", service.list())); } catch (Exception e) { return err(e); }
    }

    private ResponseEntity<Map<String, Object>> disabled() { return ResponseEntity.status(403).body(Map.of("error", "OkrTracker skill is disabled")); }
    private ResponseEntity<Map<String, Object>> bad(Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    private ResponseEntity<Map<String, Object>> err(Exception e) { return ResponseEntity.status(500).body(Map.of("error", e.getMessage())); }
}
