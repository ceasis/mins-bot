package com.minsbot.skills.recipescaler;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class RecipeScalerService {

    // All in millilitres where applicable
    private static final Map<String, Double> VOLUME_ML = Map.ofEntries(
            Map.entry("tsp", 4.929), Map.entry("tbsp", 14.787), Map.entry("cup", 236.588),
            Map.entry("ml", 1.0), Map.entry("l", 1000.0), Map.entry("floz", 29.574), Map.entry("pint", 473.176),
            Map.entry("quart", 946.353), Map.entry("gallon", 3785.411)
    );
    private static final Map<String, Double> WEIGHT_G = Map.of(
            "g", 1.0, "kg", 1000.0, "mg", 0.001, "oz", 28.3495, "lb", 453.592
    );

    public Map<String, Object> scale(List<Map<String, Object>> ingredients, int originalServings, int targetServings) {
        if (originalServings <= 0 || targetServings <= 0) throw new IllegalArgumentException("servings > 0");
        double factor = (double) targetServings / originalServings;
        List<Map<String, Object>> scaled = new ArrayList<>();
        for (Map<String, Object> ing : ingredients) {
            String name = String.valueOf(ing.getOrDefault("name", "?"));
            double amount = ((Number) ing.get("amount")).doubleValue();
            String unit = String.valueOf(ing.getOrDefault("unit", ""));
            double newAmount = amount * factor;
            scaled.add(Map.of("name", name, "amount", round(newAmount), "unit", unit));
        }
        return Map.of(
                "scaleFactor", round(factor),
                "originalServings", originalServings,
                "targetServings", targetServings,
                "ingredients", scaled
        );
    }

    public Map<String, Object> convertVolume(double value, String from, String to) {
        Double f = VOLUME_ML.get(from.toLowerCase()), t = VOLUME_ML.get(to.toLowerCase());
        if (f == null || t == null) throw new IllegalArgumentException("unknown volume unit (tsp/tbsp/cup/ml/l/floz/pint/quart/gallon)");
        double ml = value * f;
        double result = ml / t;
        return Map.of("input", Map.of("value", value, "unit", from), "output", Map.of("value", round(result), "unit", to));
    }

    public Map<String, Object> convertWeight(double value, String from, String to) {
        Double f = WEIGHT_G.get(from.toLowerCase()), t = WEIGHT_G.get(to.toLowerCase());
        if (f == null || t == null) throw new IllegalArgumentException("unknown weight unit (g/kg/mg/oz/lb)");
        double g = value * f;
        double result = g / t;
        return Map.of("input", Map.of("value", value, "unit", from), "output", Map.of("value", round(result), "unit", to));
    }

    public Map<String, Object> ovenTemp(double value, String from, String to) {
        double c = "F".equalsIgnoreCase(from) ? (value - 32) * 5 / 9 : value;
        double result = "F".equalsIgnoreCase(to) ? c * 9.0 / 5.0 + 32 : c;
        return Map.of("input", Map.of("value", value, "unit", from),
                "output", Map.of("value", round(result), "unit", to));
    }

    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
