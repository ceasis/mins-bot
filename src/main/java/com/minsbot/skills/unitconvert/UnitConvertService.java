package com.minsbot.skills.unitconvert;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class UnitConvertService {

    // All factors convert FROM the unit TO the base unit of each category.

    private static final Map<String, Double> LENGTH = Map.ofEntries(
            Map.entry("mm", 0.001), Map.entry("cm", 0.01), Map.entry("m", 1.0),
            Map.entry("km", 1000.0), Map.entry("in", 0.0254), Map.entry("ft", 0.3048),
            Map.entry("yd", 0.9144), Map.entry("mi", 1609.344), Map.entry("nmi", 1852.0)
    );

    private static final Map<String, Double> WEIGHT = Map.ofEntries(
            Map.entry("mg", 0.001), Map.entry("g", 1.0), Map.entry("kg", 1000.0),
            Map.entry("t", 1_000_000.0), Map.entry("oz", 28.349523125),
            Map.entry("lb", 453.59237), Map.entry("st", 6350.29318)
    );

    private static final Map<String, Double> TIME_SEC = Map.of(
            "ms", 0.001, "s", 1.0, "min", 60.0,
            "h", 3600.0, "d", 86400.0, "w", 604800.0
    );

    private static final Map<String, Double> DATA_BYTES = Map.ofEntries(
            Map.entry("b", 0.125), Map.entry("B", 1.0),
            Map.entry("KB", 1000.0), Map.entry("MB", 1_000_000.0),
            Map.entry("GB", 1_000_000_000.0), Map.entry("TB", 1_000_000_000_000.0),
            Map.entry("KiB", 1024.0), Map.entry("MiB", 1024.0 * 1024),
            Map.entry("GiB", Math.pow(1024, 3)), Map.entry("TiB", Math.pow(1024, 4))
    );

    public double convert(String category, double value, String from, String to) {
        return switch (category.toLowerCase()) {
            case "length" -> linear(LENGTH, value, from, to, category);
            case "weight" -> linear(WEIGHT, value, from, to, category);
            case "time" -> linear(TIME_SEC, value, from, to, category);
            case "data" -> linear(DATA_BYTES, value, from, to, category);
            case "temperature" -> temperature(value, from, to);
            default -> throw new IllegalArgumentException("Unknown category: " + category);
        };
    }

    public Map<String, Object> categories() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("length", LENGTH.keySet());
        out.put("weight", WEIGHT.keySet());
        out.put("time", TIME_SEC.keySet());
        out.put("data", DATA_BYTES.keySet());
        out.put("temperature", java.util.List.of("C", "F", "K"));
        return out;
    }

    private static double linear(Map<String, Double> table, double value, String from, String to, String cat) {
        Double fromFactor = table.get(from);
        Double toFactor = table.get(to);
        if (fromFactor == null) throw new IllegalArgumentException("Unknown " + cat + " unit: " + from);
        if (toFactor == null) throw new IllegalArgumentException("Unknown " + cat + " unit: " + to);
        return value * fromFactor / toFactor;
    }

    private static double temperature(double value, String from, String to) {
        double celsius = switch (from) {
            case "C" -> value;
            case "F" -> (value - 32) * 5.0 / 9.0;
            case "K" -> value - 273.15;
            default -> throw new IllegalArgumentException("Unknown temperature unit: " + from);
        };
        return switch (to) {
            case "C" -> celsius;
            case "F" -> celsius * 9.0 / 5.0 + 32;
            case "K" -> celsius + 273.15;
            default -> throw new IllegalArgumentException("Unknown temperature unit: " + to);
        };
    }
}
