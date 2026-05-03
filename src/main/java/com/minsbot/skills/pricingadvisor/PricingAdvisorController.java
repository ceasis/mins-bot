package com.minsbot.skills.pricingadvisor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/pricingadvisor")
public class PricingAdvisorController {

    private final PricingAdvisorService service;
    private final PricingAdvisorConfig.PricingAdvisorProperties props;

    public PricingAdvisorController(PricingAdvisorService service,
                                    PricingAdvisorConfig.PricingAdvisorProperties props) {
        this.service = service;
        this.props = props;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of("skill", "pricingadvisor", "enabled", props.isEnabled(),
                "purpose", "Recommend 3-tier pricing from competitor anchors + cost floor"));
    }

    @PostMapping("/run")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> run(@RequestBody Map<String, Object> body) {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "pricingadvisor skill is disabled"));
        try {
            List<Number> prices = (List<Number>) body.getOrDefault("competitorPrices", List.of());
            List<Double> doubles = prices.stream().map(Number::doubleValue).toList();
            Double cost = body.get("costFloor") instanceof Number n ? n.doubleValue() : null;
            Double margin = body.get("targetMargin") instanceof Number n ? n.doubleValue() : null;
            String positioning = (String) body.get("positioning");
            return ResponseEntity.ok(service.recommend(doubles, cost, margin, positioning));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
