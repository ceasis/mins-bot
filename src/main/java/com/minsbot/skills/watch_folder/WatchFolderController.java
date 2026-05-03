package com.minsbot.skills.watch_folder;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/watch-folder")
public class WatchFolderController {

    private final WatchFolderService service;

    public WatchFolderController(WatchFolderService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return service.list();
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        WatchFolderEntry e = WatchFolderEntry.fromMap(body);
        return service.create(e).toMap();
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        boolean ok = service.delete(id);
        return Map.of("deleted", ok, "id", id);
    }
}
