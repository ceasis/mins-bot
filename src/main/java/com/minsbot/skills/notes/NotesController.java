package com.minsbot.skills.notes;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/notes")
public class NotesController {

    private final NotesService service;
    private final NotesConfig.NotesProperties properties;

    public NotesController(NotesService service, NotesConfig.NotesProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "notes",
                "enabled", properties.isEnabled(),
                "storageDir", properties.getStorageDir(),
                "maxBodyChars", properties.getMaxBodyChars()
        ));
    }

    @PostMapping
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return disabled();
        try {
            String title = (String) body.get("title");
            String noteBody = (String) body.getOrDefault("body", "");
            if (noteBody.length() > properties.getMaxBodyChars()) {
                return ResponseEntity.badRequest().body(Map.of("error", "body exceeds maxBodyChars"));
            }
            List<String> tags = (List<String>) body.get("tags");
            return ResponseEntity.ok(service.create(title, noteBody, tags));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        if (!properties.isEnabled()) return disabled();
        try {
            return ResponseEntity.ok(service.get(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return disabled();
        try {
            String title = (String) body.get("title");
            String noteBody = (String) body.get("body");
            if (noteBody != null && noteBody.length() > properties.getMaxBodyChars()) {
                return ResponseEntity.badRequest().body(Map.of("error", "body exceeds maxBodyChars"));
            }
            List<String> tags = (List<String>) body.get("tags");
            return ResponseEntity.ok(service.update(id, title, noteBody, tags));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
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

    @GetMapping
    public ResponseEntity<?> list() {
        if (!properties.isEnabled()) return disabled();
        try {
            return ResponseEntity.ok(Map.of("notes", service.list()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam(required = false) String q,
                                    @RequestParam(required = false) String tag) {
        if (!properties.isEnabled()) return disabled();
        try {
            return ResponseEntity.ok(Map.of("notes", service.search(q, tag)));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<Map<String, Object>> disabled() {
        return ResponseEntity.status(403).body(Map.of("error", "Notes skill is disabled"));
    }
}
