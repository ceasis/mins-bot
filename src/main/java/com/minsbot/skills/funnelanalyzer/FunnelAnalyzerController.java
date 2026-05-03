package com.minsbot.skills.funnelanalyzer;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/funnelanalyzer")
public class FunnelAnalyzerController {

    private final FunnelAnalyzerService service;
    private final FunnelAnalyzerConfig.FunnelAnalyzerProperties props;

    public FunnelAnalyzerController(FunnelAnalyzerService service,
                                    FunnelAnalyzerConfig.FunnelAnalyzerProperties props) {
        this.service = service;
        this.props = props;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of("skill", "funnelanalyzer", "enabled", props.isEnabled(),
                "purpose", "Find biggest funnel leak + suggest stage-specific experiments"));
    }

    @PostMapping("/run")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> run(@RequestBody Map<String, Object> body) {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "funnelanalyzer skill is disabled"));
        try {
            List<Map<String, Object>> stages = (List<Map<String, Object>>) body.getOrDefault("stages", List.of());
            return ResponseEntity.ok(service.analyze(stages));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
