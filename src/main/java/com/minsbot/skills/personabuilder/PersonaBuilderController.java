package com.minsbot.skills.personabuilder;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/personabuilder")
public class PersonaBuilderController {

    private final PersonaBuilderService service;
    private final PersonaBuilderConfig.PersonaBuilderProperties props;

    public PersonaBuilderController(PersonaBuilderService service,
                                    PersonaBuilderConfig.PersonaBuilderProperties props) {
        this.service = service;
        this.props = props;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of("skill", "personabuilder", "enabled", props.isEnabled(),
                "purpose", "Build ICP profile from supplied source URLs (pains, objections, titles, vocab)"));
    }

    @PostMapping("/run")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> run(@RequestBody Map<String, Object> body) {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "personabuilder skill is disabled"));
        try {
            String biz = (String) body.get("businessType");
            List<String> sources = (List<String>) body.getOrDefault("sources", List.of());
            return ResponseEntity.ok(service.build(biz, sources));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
