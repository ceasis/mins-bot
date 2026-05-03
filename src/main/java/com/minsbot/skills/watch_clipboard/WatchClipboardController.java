package com.minsbot.skills.watch_clipboard;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/watch-clipboard")
public class WatchClipboardController {

    private final WatchClipboardService service;

    public WatchClipboardController(WatchClipboardService service) { this.service = service; }

    @GetMapping
    public List<Map<String, Object>> list() { return service.list(); }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return service.create(WatchClipboardEntry.fromMap(body)).toMap();
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        return Map.of("deleted", service.delete(id), "id", id);
    }
}
