package com.minsbot.skills.dockerfilelint;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/dockerfilelint")
public class DockerfileLintController {
    private final DockerfileLintService service;
    private final DockerfileLintConfig.DockerfileLintProperties properties;
    public DockerfileLintController(DockerfileLintService service, DockerfileLintConfig.DockerfileLintProperties properties) { this.service = service; this.properties = properties; }

    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "dockerfilelint", "enabled", properties.isEnabled())); }

    @PostMapping("/lint") public ResponseEntity<?> lint(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return disabled();
        return ResponseEntity.ok(service.lint(body.get("dockerfile")));
    }

    private ResponseEntity<Map<String, Object>> disabled() { return ResponseEntity.status(403).body(Map.of("error", "DockerfileLint skill is disabled")); }
}
