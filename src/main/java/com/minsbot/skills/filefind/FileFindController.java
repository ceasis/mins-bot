package com.minsbot.skills.filefind;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/filefind")
public class FileFindController {
    private final FileFindService svc;
    private final FileFindConfig.FileFindProperties props;
    public FileFindController(FileFindService svc, FileFindConfig.FileFindProperties props) { this.svc = svc; this.props = props; }
    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "filefind", "enabled", props.isEnabled())); }
    @GetMapping("/find") public ResponseEntity<?> find(@RequestParam String path,
                                                        @RequestParam(required = false) String glob,
                                                        @RequestParam(required = false) String regex,
                                                        @RequestParam(defaultValue = "true") boolean recursive,
                                                        @RequestParam(required = false) Integer limit) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.find(path, glob, regex, recursive, limit)); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
