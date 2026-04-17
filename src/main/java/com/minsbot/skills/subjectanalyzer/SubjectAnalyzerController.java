package com.minsbot.skills.subjectanalyzer;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/subjectanalyzer")
public class SubjectAnalyzerController {

    private final SubjectAnalyzerService service;
    private final SubjectAnalyzerConfig.SubjectAnalyzerProperties properties;

    public SubjectAnalyzerController(SubjectAnalyzerService service,
                                     SubjectAnalyzerConfig.SubjectAnalyzerProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "subjectanalyzer",
                "enabled", properties.isEnabled(),
                "maxLength", properties.getMaxLength()
        ));
    }

    @PostMapping("/analyze")
    public ResponseEntity<?> analyze(@RequestBody Map<String, String> body) {
        if (!properties.isEnabled()) return disabled();
        String subject = body.get("subject");
        if (subject == null) return ResponseEntity.badRequest().body(Map.of("error", "subject required"));
        if (subject.length() > properties.getMaxLength()) {
            return ResponseEntity.badRequest().body(Map.of("error", "subject exceeds maxLength"));
        }
        return ResponseEntity.ok(service.analyze(subject));
    }

    private ResponseEntity<Map<String, Object>> disabled() {
        return ResponseEntity.status(403).body(Map.of("error", "SubjectAnalyzer skill is disabled"));
    }
}
