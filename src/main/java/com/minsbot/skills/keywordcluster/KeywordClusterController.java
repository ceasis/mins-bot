package com.minsbot.skills.keywordcluster;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/keywordcluster")
public class KeywordClusterController {

    private final KeywordClusterService service;
    private final KeywordClusterConfig.KeywordClusterProperties props;

    public KeywordClusterController(KeywordClusterService service,
                                    KeywordClusterConfig.KeywordClusterProperties props) {
        this.service = service;
        this.props = props;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of("skill", "keywordcluster", "enabled", props.isEnabled(),
                "purpose", "Group keywords by intent + head term, suggest article briefs"));
    }

    @PostMapping("/run")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> run(@RequestBody Map<String, Object> body) {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "keywordcluster skill is disabled"));
        try {
            List<String> kws = (List<String>) body.getOrDefault("keywords", List.of());
            return ResponseEntity.ok(service.cluster(kws));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
