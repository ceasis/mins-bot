package com.minsbot.skills.powerctl;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/powerctl")
public class PowerCtlController {
    private final PowerCtlService svc;
    private final PowerCtlConfig.PowerCtlProperties props;
    public PowerCtlController(PowerCtlService svc, PowerCtlConfig.PowerCtlProperties props) { this.svc = svc; this.props = props; }
    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "powerctl", "enabled", props.isEnabled(), "allowShutdown", props.isAllowShutdown())); }
    @PostMapping("/lock") public ResponseEntity<?> lock() { return guard(svc::lock); }
    @PostMapping("/sleep") public ResponseEntity<?> sleep() { return guard(svc::sleep); }
    @PostMapping("/hibernate") public ResponseEntity<?> hib() { return guard(svc::hibernate); }
    @PostMapping("/shutdown") public ResponseEntity<?> shutdown(@RequestParam(defaultValue = "60") int delaySeconds) { return guard(() -> svc.shutdown(delaySeconds)); }
    @PostMapping("/cancel-shutdown") public ResponseEntity<?> cancel() { return guard(svc::cancelShutdown); }
    @PostMapping("/restart") public ResponseEntity<?> restart(@RequestParam(defaultValue = "60") int delaySeconds) { return guard(() -> svc.restart(delaySeconds)); }

    interface T { Object call() throws Exception; }
    private ResponseEntity<?> guard(T t) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(t.call()); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
