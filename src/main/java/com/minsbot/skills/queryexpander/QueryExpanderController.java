package com.minsbot.skills.queryexpander;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/queryexpander")
public class QueryExpanderController {
    private final QueryExpanderService service;
    private final QueryExpanderConfig.QueryExpanderProperties properties;

    public QueryExpanderController(QueryExpanderService service, QueryExpanderConfig.QueryExpanderProperties properties) {
        this.service = service; this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of("skill", "queryexpander", "enabled", properties.isEnabled(),
                "maxInputChars", properties.getMaxInputChars()));
    }

    @PostMapping("/expand")
    public ResponseEntity<?> expand(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "QueryExpander skill is disabled"));
        String query = body.get("query");
        if (query == null) return ResponseEntity.badRequest().body(Map.of("error", "query required"));
        if (query.length() > properties.getMaxInputChars()) {
            return ResponseEntity.badRequest().body(Map.of("error", "query exceeds maxInputChars"));
        }
        return ResponseEntity.ok(service.expand(query));
    }
}
