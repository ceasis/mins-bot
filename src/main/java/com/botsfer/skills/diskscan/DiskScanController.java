package com.botsfer.skills.diskscan;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/diskscan")
public class DiskScanController {

    private final DiskScanService diskScanService;
    private final DiskScanConfig.DiskScanProperties properties;

    public DiskScanController(DiskScanService diskScanService,
                              DiskScanConfig.DiskScanProperties properties) {
        this.diskScanService = diskScanService;
        this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "diskscan",
                "enabled", properties.isEnabled(),
                "maxDepth", properties.getMaxDepth(),
                "maxResults", properties.getMaxResults(),
                "blockedPathCount", properties.getBlockedPaths().size()
        ));
    }

    @GetMapping("/roots")
    public ResponseEntity<?> listRoots() {
        if (!properties.isEnabled()) return disabled();
        return ResponseEntity.ok(Map.of("roots", diskScanService.listRoots()));
    }

    @GetMapping("/browse")
    public ResponseEntity<?> browse(@RequestParam String path) {
        if (!properties.isEnabled()) return disabled();
        try {
            return ResponseEntity.ok(diskScanService.browse(path));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/info")
    public ResponseEntity<?> info(@RequestParam String path) {
        if (!properties.isEnabled()) return disabled();
        try {
            return ResponseEntity.ok(diskScanService.getInfo(path));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam String basePath,
                                    @RequestParam String pattern) {
        if (!properties.isEnabled()) return disabled();
        try {
            return ResponseEntity.ok(diskScanService.search(basePath, pattern));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<Map<String, Object>> disabled() {
        return ResponseEntity.status(403).body(Map.of("error", "Disk scan skill is disabled"));
    }
}
