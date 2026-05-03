package com.minsbot.skills.cohortcalc;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/cohortcalc")
public class CohortCalcController {

    private final CohortCalcService service;
    private final CohortCalcConfig.CohortCalcProperties props;

    public CohortCalcController(CohortCalcService service, CohortCalcConfig.CohortCalcProperties props) {
        this.service = service;
        this.props = props;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of("skill", "cohortcalc", "enabled", props.isEnabled(),
                "purpose", "Retention curve + LTV + CAC payback from cohort signups"));
    }

    @PostMapping("/run")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> run(@RequestBody Map<String, Object> body) {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "cohortcalc skill is disabled"));
        try {
            List<Map<String, Object>> cohorts = (List<Map<String, Object>>) body.getOrDefault("cohorts", List.of());
            Double arpu = body.get("arpu") instanceof Number n ? n.doubleValue() : null;
            Double cac = body.get("cac") instanceof Number n ? n.doubleValue() : null;
            Double margin = body.get("grossMargin") instanceof Number n ? n.doubleValue() : null;
            return ResponseEntity.ok(service.compute(cohorts, arpu, cac, margin));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
