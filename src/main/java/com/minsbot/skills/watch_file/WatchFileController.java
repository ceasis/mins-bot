package com.minsbot.skills.watch_file;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/watch-file")
public class WatchFileController {

    private final WatchFileService service;

    public WatchFileController(WatchFileService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return service.list();
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        WatchFileEntry e = WatchFileEntry.fromMap(body);
        return service.create(e).toMap();
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        boolean ok = service.delete(id);
        return Map.of("deleted", ok, "id", id);
    }

    @PostMapping("/{id}/check")
    public Map<String, Object> check(@PathVariable String id) {
        return Map.of("status", service.checkNow(id));
    }
}
