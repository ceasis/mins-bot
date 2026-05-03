package com.minsbot.skills.watch_disk;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/watch-disk")
public class WatchDiskController {

    private final WatchDiskService service;

    public WatchDiskController(WatchDiskService service) { this.service = service; }

    @GetMapping
    public List<Map<String, Object>> list() { return service.list(); }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return service.create(WatchDiskEntry.fromMap(body)).toMap();
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        return Map.of("deleted", service.delete(id), "id", id);
    }

    @PostMapping("/{id}/check")
    public Map<String, Object> check(@PathVariable String id) {
        return Map.of("status", service.checkNow(id));
    }
}
