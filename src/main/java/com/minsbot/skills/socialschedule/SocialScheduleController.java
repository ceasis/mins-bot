package com.minsbot.skills.socialschedule;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/socialschedule")
public class SocialScheduleController {

    private final SocialScheduleService service;
    private final SocialScheduleConfig.SocialScheduleProperties props;

    public SocialScheduleController(SocialScheduleService service,
                                    SocialScheduleConfig.SocialScheduleProperties props) {
        this.service = service;
        this.props = props;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "skill", "socialschedule",
                "enabled", props.isEnabled(),
                "purpose", "Generate per-platform social post variants with hashtags + suggested times"
        ));
    }

    @PostMapping("/run")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> run(@RequestBody Map<String, Object> body) {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "socialschedule skill is disabled"));
        try {
            String brief = (String) body.get("brief");
            String cta = (String) body.get("cta");
            List<String> keywords = (List<String>) body.getOrDefault("keywords", List.of());
            List<String> platforms = (List<String>) body.getOrDefault("platforms", List.of());
            return ResponseEntity.ok(service.generate(brief, cta, keywords, platforms));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
