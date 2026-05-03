package com.minsbot.skills.filerename;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/filerename")
public class FileRenameController {
    private final FileRenameService svc;
    private final FileRenameConfig.FileRenameProperties props;
    public FileRenameController(FileRenameService svc, FileRenameConfig.FileRenameProperties props) { this.svc = svc; this.props = props; }
    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "filerename", "enabled", props.isEnabled())); }
    @PostMapping("/rename") public ResponseEntity<?> rename(@RequestParam String path,
                                                            @RequestParam(required = false) String glob,
                                                            @RequestParam String regex,
                                                            @RequestParam String replacement,
                                                            @RequestParam(defaultValue = "true") boolean dryRun) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.rename(path, glob, regex, replacement, dryRun)); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
