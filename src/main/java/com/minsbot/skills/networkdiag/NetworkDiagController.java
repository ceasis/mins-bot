package com.minsbot.skills.networkdiag;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/networkdiag")
public class NetworkDiagController {
    private final NetworkDiagService svc;
    private final NetworkDiagConfig.NetworkDiagProperties props;
    public NetworkDiagController(NetworkDiagService svc, NetworkDiagConfig.NetworkDiagProperties props) { this.svc = svc; this.props = props; }

    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "networkdiag", "enabled", props.isEnabled())); }

    @GetMapping("/ping") public ResponseEntity<?> ping(@RequestParam String host, @RequestParam(defaultValue = "4") int count) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.ping(host, count)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
    @GetMapping("/traceroute") public ResponseEntity<?> trace(@RequestParam String host) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.traceroute(host)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
    @GetMapping("/dns") public ResponseEntity<?> dns(@RequestParam String host) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.dns(host)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
    @GetMapping("/diagnose") public ResponseEntity<?> diagnose(@RequestParam String url) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try { return ResponseEntity.ok(svc.diagnose(url)); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
