package com.minsbot.skills.gitquickactions;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/gitquickactions")
public class GitQuickActionsController {
    private final GitQuickActionsService svc;
    private final GitQuickActionsConfig.GitQuickActionsProperties props;
    public GitQuickActionsController(GitQuickActionsService svc, GitQuickActionsConfig.GitQuickActionsProperties props) { this.svc = svc; this.props = props; }
    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "gitquickactions", "enabled", props.isEnabled())); }
    @GetMapping("/snapshot") public ResponseEntity<?> snap(@RequestParam(required = false) String path) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.snapshot(path)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
    @GetMapping("/stale-branches") public ResponseEntity<?> stale(@RequestParam(required = false) String path, @RequestParam(defaultValue = "30") int days) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.staleBranches(path, days)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
