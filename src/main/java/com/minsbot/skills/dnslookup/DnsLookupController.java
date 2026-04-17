package com.minsbot.skills.dnslookup;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/dnslookup")
public class DnsLookupController {

    private final DnsLookupService service;
    private final DnsLookupConfig.DnsLookupProperties properties;

    public DnsLookupController(DnsLookupService service,
                               DnsLookupConfig.DnsLookupProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "dnslookup",
                "enabled", properties.isEnabled(),
                "timeoutMs", properties.getTimeoutMs()
        ));
    }

    @GetMapping("/lookup")
    public ResponseEntity<?> lookup(@RequestParam String domain,
                                    @RequestParam(required = false) String types) {
        if (!properties.isEnabled()) return disabled();
        try {
            List<String> typeList = types == null ? null : Arrays.asList(types.split(","));
            return ResponseEntity.ok(service.lookup(domain, typeList));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<Map<String, Object>> disabled() {
        return ResponseEntity.status(403).body(Map.of("error", "DnsLookup skill is disabled"));
    }
}
