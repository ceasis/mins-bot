package com.minsbot.skills.filegrep;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/filegrep")
public class FileGrepController {
    private final FileGrepService svc;
    private final FileGrepConfig.FileGrepProperties props;
    public FileGrepController(FileGrepService svc, FileGrepConfig.FileGrepProperties props) { this.svc = svc; this.props = props; }
    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "filegrep", "enabled", props.isEnabled())); }
    @GetMapping("/grep") public ResponseEntity<?> grep(@RequestParam String path,
                                                        @RequestParam String pattern,
                                                        @RequestParam(required = false) String glob,
                                                        @RequestParam(defaultValue = "true") boolean recursive,
                                                        @RequestParam(defaultValue = "false") boolean caseInsensitive) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.grep(path, pattern, glob, recursive, caseInsensitive)); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
