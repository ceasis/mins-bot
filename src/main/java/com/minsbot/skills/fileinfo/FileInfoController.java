package com.minsbot.skills.fileinfo;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/fileinfo")
public class FileInfoController {
    private final FileInfoService svc;
    private final FileInfoConfig.FileInfoProperties props;
    public FileInfoController(FileInfoService svc, FileInfoConfig.FileInfoProperties props) { this.svc = svc; this.props = props; }
    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "fileinfo", "enabled", props.isEnabled())); }
    @GetMapping("/get") public ResponseEntity<?> get(@RequestParam String path, @RequestParam(defaultValue = "false") boolean withHash) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.info(path, withHash)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
