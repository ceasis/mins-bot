package com.minsbot.skills.proposalwriter;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/proposalwriter")
public class ProposalWriterController {

    private final ProposalWriterService service;
    private final ProposalWriterConfig.ProposalWriterProperties props;

    public ProposalWriterController(ProposalWriterService service,
                                    ProposalWriterConfig.ProposalWriterProperties props) {
        this.service = service;
        this.props = props;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of("skill", "proposalwriter", "enabled", props.isEnabled(),
                "purpose", "Generate 3 cold-pitch proposal variants from a lead"));
    }

    @PostMapping("/run")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> run(@RequestBody Map<String, Object> body) {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "proposalwriter skill is disabled"));
        try {
            String snippet = (String) body.get("leadSnippet");
            String yourName = (String) body.get("yourName");
            String yourService = (String) body.get("yourService");
            String priceAnchor = (String) body.get("priceAnchor");
            List<String> proof = (List<String>) body.getOrDefault("proofPoints", List.of());
            return ResponseEntity.ok(service.write(snippet, yourName, yourService, priceAnchor, proof));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
