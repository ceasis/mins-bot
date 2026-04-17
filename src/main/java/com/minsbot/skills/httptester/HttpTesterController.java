package com.minsbot.skills.httptester;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/httptester")
public class HttpTesterController {
    private final HttpTesterService service;
    private final HttpTesterConfig.HttpTesterProperties properties;
    public HttpTesterController(HttpTesterService service, HttpTesterConfig.HttpTesterProperties properties) { this.service = service; this.properties = properties; }

    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "httptester", "enabled", properties.isEnabled(), "timeoutMs", properties.getTimeoutMs(), "allowedHostCount", properties.getAllowedHosts().size())); }

    @PostMapping("/execute") @SuppressWarnings("unchecked") public ResponseEntity<?> execute(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "HttpTester skill is disabled"));
        try {
            String method = (String) body.getOrDefault("method", "GET");
            String url = (String) body.get("url");
            Map<String, String> headers = (Map<String, String>) body.get("headers");
            String reqBody = (String) body.get("body");
            return ResponseEntity.ok(service.execute(method, url, headers, reqBody));
        } catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
        catch (Exception e) { return ResponseEntity.status(502).body(Map.of("error", e.getMessage())); }
    }
}
