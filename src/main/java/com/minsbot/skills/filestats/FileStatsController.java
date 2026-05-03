package com.minsbot.skills.filestats;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/filestats")
public class FileStatsController {
    private final FileStatsService svc;
    private final FileStatsConfig.FileStatsProperties props;
    public FileStatsController(FileStatsService svc, FileStatsConfig.FileStatsProperties props) { this.svc = svc; this.props = props; }
    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "filestats", "enabled", props.isEnabled())); }
    @GetMapping("/count") public ResponseEntity<?> count(@RequestParam String path,
                                                          @RequestParam(defaultValue = "false") boolean recursive,
                                                          @RequestParam(defaultValue = "false") boolean includeDotfiles) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.countByExtension(path, recursive, includeDotfiles)); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
