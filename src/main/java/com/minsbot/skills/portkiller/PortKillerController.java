package com.minsbot.skills.portkiller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/portkiller")
public class PortKillerController {

    private final PortKillerService service;
    private final PortKillerConfig.PortKillerProperties props;

    public PortKillerController(PortKillerService service, PortKillerConfig.PortKillerProperties props) {
        this.service = service;
        this.props = props;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of("skill", "portkiller", "enabled", props.isEnabled(),
                "protectedPorts", props.getProtectedPorts(),
                "purpose", "Find and kill processes listening on a TCP port"));
    }

    @GetMapping("/find")
    public ResponseEntity<?> find(@RequestParam int port) {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "portkiller skill is disabled"));
        try { return ResponseEntity.ok(service.findOnPort(port)); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    @PostMapping("/kill")
    public ResponseEntity<?> kill(@RequestParam int port,
                                  @RequestParam(defaultValue = "true") boolean force) {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "portkiller skill is disabled"));
        try { return ResponseEntity.ok(service.kill(port, force)); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
