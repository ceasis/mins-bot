package com.minsbot.skills.petentertainment;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/petentertainment")
public class PetEntertainmentController {
    private final PetEntertainmentService svc;
    private final PetEntertainmentConfig.PetEntertainmentProperties props;
    public PetEntertainmentController(PetEntertainmentService svc, PetEntertainmentConfig.PetEntertainmentProperties props) { this.svc = svc; this.props = props; }
    @GetMapping("/status") public ResponseEntity<?> status() { return ResponseEntity.ok(Map.of("skill", "petentertainment", "enabled", props.isEnabled())); }
    @PostMapping("/play") public ResponseEntity<?> play(@RequestParam(required = false) String preset, @RequestParam(required = false) String query) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        try {
            if (query != null && !query.isBlank()) return ResponseEntity.ok(svc.playCustom(query));
            return ResponseEntity.ok(svc.play(preset));
        } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
    @GetMapping("/presets") public ResponseEntity<?> presets() {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        return ResponseEntity.ok(svc.listPresets());
    }
    @GetMapping("/story-prompt") public ResponseEntity<?> story(@RequestParam(defaultValue = "pet") String petType, @RequestParam(defaultValue = "5") int minutes) {
        if (!props.isEnabled()) return ResponseEntity.status(403).body(Map.of("error", "disabled"));
        return ResponseEntity.ok(svc.storyPrompt(petType, minutes));
    }
}
