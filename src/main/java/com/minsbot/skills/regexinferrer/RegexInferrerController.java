package com.minsbot.skills.regexinferrer;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/regexinferrer")
public class RegexInferrerController {
    private final RegexInferrerService service;
    private final RegexInferrerConfig.RegexInferrerProperties properties;
    public RegexInferrerController(RegexInferrerService service, RegexInferrerConfig.RegexInferrerProperties properties) { this.service = service; this.properties = properties; }

    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "regexinferrer", "enabled", properties.isEnabled())); }

    @PostMapping("/infer") @SuppressWarnings("unchecked") public ResponseEntity<?> infer(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "RegexInferrer skill is disabled"));
        try { return ResponseEntity.ok(service.infer((List<String>) body.get("examples"))); } catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}
