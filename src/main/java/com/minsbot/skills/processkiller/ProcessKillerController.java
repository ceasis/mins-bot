package com.minsbot.skills.processkiller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/processkiller")
public class ProcessKillerController {
    private final ProcessKillerService svc;
    private final ProcessKillerConfig.ProcessKillerProperties props;
    public ProcessKillerController(ProcessKillerService svc, ProcessKillerConfig.ProcessKillerProperties props) { this.svc = svc; this.props = props; }

    @GetMapping("/status")
    public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "processkiller", "enabled", props.isEnabled(), "protectedNames", props.getProtectedNames())); }

    @GetMapping("/find")
    public ResponseEntity<?> find(@RequestParam String name) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.findByName(name)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    @PostMapping("/kill-pid")
    public ResponseEntity<?> killPid(@RequestParam int pid, @RequestParam(defaultValue = "true") boolean force) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.killByPid(pid, force)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    @PostMapping("/kill-name")
    public ResponseEntity<?> killName(@RequestParam String name, @RequestParam(defaultValue = "true") boolean force) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.killByName(name, force)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    @GetMapping("/top-cpu")
    public ResponseEntity<?> topCpu(@RequestParam(defaultValue = "10") int n) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(Map.of("top", svc.topByCpu(n))); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    @GetMapping("/top-memory")
    public ResponseEntity<?> topMem(@RequestParam(defaultValue = "10") int n) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(Map.of("top", svc.topByMemory(n))); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
