package com.minsbot.skills.yamltools;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/yamltools")
public class YamlToolsController {
    private final YamlToolsService service;
    private final YamlToolsConfig.YamlToolsProperties properties;
    public YamlToolsController(YamlToolsService service, YamlToolsConfig.YamlToolsProperties properties) { this.service = service; this.properties = properties; }

    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "yamltools", "enabled", properties.isEnabled(), "maxInputBytes", properties.getMaxInputBytes())); }

    @PostMapping("/validate") public ResponseEntity<?> validate(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return disabled();
        String y = body.get("yaml"); if (y == null) return bad("yaml required");
        if (y.length() > properties.getMaxInputBytes()) return bad("exceeds maxInputBytes");
        return ResponseEntity.ok(service.validate(y));
    }

    @PostMapping("/yaml-to-json") public ResponseEntity<?> toJson(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(Map.of("json", service.yamlToJson(body.get("yaml")))); } catch (Exception e) { return bad(e.getMessage()); }
    }

    @PostMapping("/json-to-yaml") public ResponseEntity<?> toYaml(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(Map.of("yaml", service.jsonToYaml(body.get("json")))); } catch (Exception e) { return bad(e.getMessage()); }
    }

    @PostMapping("/pretty") public ResponseEntity<?> pretty(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return disabled();
        try { return ResponseEntity.ok(Map.of("yaml", service.prettyPrint(body.get("yaml")))); } catch (Exception e) { return bad(e.getMessage()); }
    }

    private ResponseEntity<Map<String, Object>> disabled() { return ResponseEntity.status(403).body(Map.of("error", "YamlTools skill is disabled")); }
    private ResponseEntity<Map<String, Object>> bad(String m) { return ResponseEntity.badRequest().body(Map.of("error", m)); }
}
