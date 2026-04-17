package com.minsbot.skills.stockindicators;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/stockindicators")
public class StockIndicatorsController {
    private final StockIndicatorsService service;
    private final StockIndicatorsConfig.StockIndicatorsProperties properties;

    public StockIndicatorsController(StockIndicatorsService service, StockIndicatorsConfig.StockIndicatorsProperties properties) {
        this.service = service; this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of("skill", "stockindicators", "enabled", properties.isEnabled(),
                "maxPricePoints", properties.getMaxPricePoints()));
    }

    @PostMapping("/sma")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> sma(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return disabled();
        try {
            List<Number> prices = (List<Number>) body.get("prices");
            int period = ((Number) body.getOrDefault("period", 14)).intValue();
            return ResponseEntity.ok(Map.of("period", period, "sma", service.sma(prices.stream().map(Number::doubleValue).toList(), period)));
        } catch (Exception e) { return bad(e); }
    }

    @PostMapping("/ema")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> ema(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return disabled();
        try {
            List<Number> prices = (List<Number>) body.get("prices");
            int period = ((Number) body.getOrDefault("period", 14)).intValue();
            return ResponseEntity.ok(Map.of("period", period, "ema", service.ema(prices.stream().map(Number::doubleValue).toList(), period)));
        } catch (Exception e) { return bad(e); }
    }

    @PostMapping("/rsi")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> rsi(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return disabled();
        try {
            List<Number> prices = (List<Number>) body.get("prices");
            int period = ((Number) body.getOrDefault("period", 14)).intValue();
            return ResponseEntity.ok(Map.of("period", period, "rsi", service.rsi(prices.stream().map(Number::doubleValue).toList(), period)));
        } catch (Exception e) { return bad(e); }
    }

    @PostMapping("/macd")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> macd(@RequestBody Map<String, Object> body) {
        if (!properties.isEnabled()) return disabled();
        try {
            List<Number> prices = (List<Number>) body.get("prices");
            int fast = ((Number) body.getOrDefault("fast", 12)).intValue();
            int slow = ((Number) body.getOrDefault("slow", 26)).intValue();
            int signal = ((Number) body.getOrDefault("signal", 9)).intValue();
            return ResponseEntity.ok(service.macd(prices.stream().map(Number::doubleValue).toList(), fast, slow, signal));
        } catch (Exception e) { return bad(e); }
    }

    private ResponseEntity<Map<String, Object>> disabled() { return ResponseEntity.status(403).body(Map.of("error", "StockIndicators skill is disabled")); }
    private ResponseEntity<Map<String, Object>> bad(Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
}
