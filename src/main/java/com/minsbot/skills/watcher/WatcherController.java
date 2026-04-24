package com.minsbot.skills.watcher;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/skills/watcher")
public class WatcherController {

    private final WatcherService service;
    private final WatcherConfig.WatcherProperties properties;

    public WatcherController(WatcherService service, WatcherConfig.WatcherProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "watcher",
                "enabled", properties.isEnabled(),
                "storageDir", properties.getStorageDir(),
                "tickIntervalSeconds", properties.getTickIntervalSeconds(),
                "minIntervalSeconds", properties.getMinIntervalSeconds(),
                "adapters", service.adapterNames()
        ));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return disabled();
        try {
            Watcher w = new Watcher();
            w.label = str(body.get("label"));
            w.url = str(body.get("url"));
            w.adapter = str(body.get("adapter"));
            w.target = str(body.get("target"));
            w.notifyEmail = str(body.get("notifyEmail"));
            w.notifyWebhook = str(body.get("notifyWebhook"));
            Object interval = body.get("intervalSeconds");
            w.intervalSeconds = interval instanceof Number ? ((Number) interval).intValue() : 900;
            Object mp = body.get("maxPrice");
            w.maxPrice = mp instanceof Number ? ((Number) mp).doubleValue() : 0.0;
            return ResponseEntity.ok(service.create(w).toMap());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<?> list() {
        if (!properties.isEnabled()) return disabled();
        try {
            List<Map<String, Object>> out = service.list().stream()
                    .map(Watcher::toMap).collect(Collectors.toList());
            return ResponseEntity.ok(Map.of("watchers", out));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        if (!properties.isEnabled()) return disabled();
        try {
            service.delete(id);
            return ResponseEntity.ok(Map.of("deleted", id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/trigger")
    public ResponseEntity<?> trigger(@PathVariable String id) {
        if (!properties.isEnabled()) return disabled();
        try {
            return ResponseEntity.ok(service.triggerNow(id).toMap());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    private ResponseEntity<Map<String, Object>> disabled() {
        return ResponseEntity.status(403).body(Map.of("error", "Watcher skill is disabled"));
    }
}
