package com.minsbot.skills.adcopygen;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/adcopygen")
public class AdCopyGenController {

    private final AdCopyGenService service;
    private final AdCopyGenConfig.AdCopyGenProperties props;

    public AdCopyGenController(AdCopyGenService service, AdCopyGenConfig.AdCopyGenProperties props) {
        this.service = service;
        this.props = props;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "adcopygen",
                "enabled", props.isEnabled(),
                "purpose", "Generate length-validated ad copy variants for Google/Meta"
        ));
    }

    @PostMapping("/run")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> run(@RequestBody Map<String, Object> body) {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "adcopygen skill is disabled"));
        try {
            String product = (String) body.get("product");
            String audience = (String) body.get("audience");
            String benefit = (String) body.get("benefit");
            List<String> keywords = (List<String>) body.getOrDefault("keywords", List.of());
            Integer max = body.get("maxVariants") instanceof Number n ? n.intValue() : null;
            return ResponseEntity.ok(service.generate(product, audience, benefit, keywords, max));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
