package com.minsbot.skills.netinfo;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/netinfo")
public class NetInfoController {

    private final NetInfoService service;
    private final NetInfoConfig.NetInfoProperties properties;

    public NetInfoController(NetInfoService service, NetInfoConfig.NetInfoProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "netinfo",
                "enabled", properties.isEnabled(),
                "portCheckTimeoutMs", properties.getPortCheckTimeoutMs(),
                "allowedPortCheckHostCount", properties.getAllowedPortCheckHosts().size()
        ));
    }

    @GetMapping("/host")
    public ResponseEntity<?> host() {
        if (!properties.isEnabled()) return disabled();
        return ResponseEntity.ok(service.hostInfo());
    }

    @GetMapping("/interfaces")
    public ResponseEntity<?> interfaces() {
        if (!properties.isEnabled()) return disabled();
        try {
            return ResponseEntity.ok(Map.of("interfaces", service.interfaces()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/public-ip")
    public ResponseEntity<?> publicIp() {
        if (!properties.isEnabled()) return disabled();
        try {
            return ResponseEntity.ok(Map.of("ip", service.publicIp()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/port")
    public ResponseEntity<?> port(@RequestParam String host, @RequestParam int port) {
        if (!properties.isEnabled()) return disabled();
        try {
            return ResponseEntity.ok(service.checkPort(host, port));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/resolve")
    public ResponseEntity<?> resolve(@RequestParam String host) {
        if (!properties.isEnabled()) return disabled();
        try {
            return ResponseEntity.ok(service.resolveHost(host));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<Map<String, Object>> disabled() {
        return ResponseEntity.status(403).body(Map.of("error", "NetInfo skill is disabled"));
    }
}
