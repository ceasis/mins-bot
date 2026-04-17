package com.minsbot.skills.medicalunits;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class MedicalUnitsService {

    // Conversion factor: mg/dL -> mmol/L is value / factor
    private static final Map<String, Double> ANALYTES = new LinkedHashMap<>();
    static {
        ANALYTES.put("glucose", 18.016);
        ANALYTES.put("cholesterol", 38.67);
        ANALYTES.put("triglycerides", 88.57);
        ANALYTES.put("urea-nitrogen", 2.801);
        ANALYTES.put("creatinine", 0.0884);
        ANALYTES.put("bilirubin", 58.5);
        ANALYTES.put("uric-acid", 16.81);
        ANALYTES.put("calcium", 4.008);
        ANALYTES.put("magnesium", 2.433);
        ANALYTES.put("phosphorus", 3.097);
        ANALYTES.put("albumin", 0.01);
        ANALYTES.put("iron", 5.585);
    }

    public Map<String, Object> convert(String analyte, double value, String fromUnit, String toUnit) {
        String key = analyte.toLowerCase();
        Double factor = ANALYTES.get(key);
        if (factor == null) throw new IllegalArgumentException("Unknown analyte: " + analyte + " (supported: " + ANALYTES.keySet() + ")");
        double result;
        if (fromUnit.equalsIgnoreCase("mg/dL") && toUnit.equalsIgnoreCase("mmol/L")) result = value / factor;
        else if (fromUnit.equalsIgnoreCase("mmol/L") && toUnit.equalsIgnoreCase("mg/dL")) result = value * factor;
        else if (fromUnit.equalsIgnoreCase(toUnit)) result = value;
        else throw new IllegalArgumentException("unsupported unit pair. Use mg/dL <-> mmol/L");
        return Map.of(
                "analyte", key,
                "input", Map.of("value", value, "unit", fromUnit),
                "output", Map.of("value", Math.round(result * 10_000.0) / 10_000.0, "unit", toUnit),
                "conversionFactor", factor
        );
    }

    public Set<String> supportedAnalytes() { return ANALYTES.keySet(); }

    public Map<String, Object> tempConvert(double value, String from, String to) {
        double c = switch (from.toUpperCase()) {
            case "C" -> value;
            case "F" -> (value - 32) * 5 / 9;
            case "K" -> value - 273.15;
            default -> throw new IllegalArgumentException("from unit C/F/K");
        };
        double result = switch (to.toUpperCase()) {
            case "C" -> c;
            case "F" -> c * 9.0 / 5.0 + 32;
            case "K" -> c + 273.15;
            default -> throw new IllegalArgumentException("to unit C/F/K");
        };
        return Map.of("input", Map.of("value", value, "unit", from), "output", Map.of("value", Math.round(result * 100.0) / 100.0, "unit", to));
    }
}
