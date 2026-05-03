package com.minsbot.skills.abtestplanner;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/skills/abtestplanner")
public class AbTestPlannerController {

    private final AbTestPlannerService service;
    private final AbTestPlannerConfig.AbTestPlannerProperties props;

    public AbTestPlannerController(AbTestPlannerService service,
                                   AbTestPlannerConfig.AbTestPlannerProperties props) {
        this.service = service;
        this.props = props;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of("skill", "abtestplanner", "enabled", props.isEnabled(),
                "purpose", "Sample size / days-to-significance for A/B tests + result evaluation"));
    }

    @PostMapping("/plan")
    public ResponseEntity<?> plan(@RequestBody Map<String, Object> body) {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "abtestplanner skill is disabled"));
        try {
            double baseline = ((Number) body.get("baselineRate")).doubleValue();
            double mde = ((Number) body.get("mdeRelative")).doubleValue();
            double traffic = body.get("dailyTraffic") instanceof Number n ? n.doubleValue() : 0;
            int variants = body.get("numVariants") instanceof Number n ? n.intValue() : 2;
            return ResponseEntity.ok(service.plan(baseline, mde, traffic, variants));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/evaluate")
    public ResponseEntity<?> evaluate(@RequestBody Map<String, Object> body) {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "abtestplanner skill is disabled"));
        try {
            long aN = ((Number) body.get("aN")).longValue();
            long aConv = ((Number) body.get("aConv")).longValue();
            long bN = ((Number) body.get("bN")).longValue();
            long bConv = ((Number) body.get("bConv")).longValue();
            return ResponseEntity.ok(service.evaluate(aN, aConv, bN, bConv));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
