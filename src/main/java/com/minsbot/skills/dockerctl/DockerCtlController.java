package com.minsbot.skills.dockerctl;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/dockerctl")
public class DockerCtlController {
    private final DockerCtlService svc;
    private final DockerCtlConfig.DockerCtlProperties props;
    public DockerCtlController(DockerCtlService svc, DockerCtlConfig.DockerCtlProperties props) { this.svc = svc; this.props = props; }
    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "dockerctl", "enabled", props.isEnabled())); }
    @GetMapping("/ps") public ResponseEntity<?> ps(@RequestParam(defaultValue = "false") boolean all) { return guard(() -> svc.ps(all)); }
    @PostMapping("/stop") public ResponseEntity<?> stop(@RequestParam String name) { return guard(() -> svc.stop(name)); }
    @PostMapping("/start") public ResponseEntity<?> start(@RequestParam String name) { return guard(() -> svc.start(name)); }
    @PostMapping("/restart") public ResponseEntity<?> restart(@RequestParam String name) { return guard(() -> svc.restart(name)); }
    @GetMapping("/logs") public ResponseEntity<?> logs(@RequestParam String name, @RequestParam(defaultValue = "100") int tail) { return guard(() -> svc.logs(name, tail)); }
    @PostMapping("/prune") public ResponseEntity<?> prune() { return guard(svc::prune); }
    @GetMapping("/images") public ResponseEntity<?> images() { return guard(svc::images); }

    interface T { Object call() throws Exception; }
    private ResponseEntity<?> guard(T t) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(t.call()); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
