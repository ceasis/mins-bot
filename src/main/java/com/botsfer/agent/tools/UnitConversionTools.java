package com.botsfer.agent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class UnitConversionTools {

    private final ToolExecutionNotifier notifier;

    public UnitConversionTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @Tool(description = "Convert between common units: length (miles, km, feet, m), weight (lb, kg), temperature (celsius, fahrenheit). " +
            "Example: '5 miles to km', '100 fahrenheit to celsius', '70 kg to lb'.")
    public String convert(
            @ToolParam(description = "Numeric value to convert") double value,
            @ToolParam(description = "Source unit: mile, km, foot, m, lb, kg, celsius, fahrenheit") String fromUnit,
            @ToolParam(description = "Target unit: mile, km, foot, m, lb, kg, celsius, fahrenheit") String toUnit) {
        if (fromUnit == null || toUnit == null) return "From and to units are required.";
        notifier.notify("Converting: " + value + " " + fromUnit + " to " + toUnit);
        String from = fromUnit.trim().toLowerCase().replace("°", "").replace("degrees", "").replace("degree", "");
        String to = toUnit.trim().toLowerCase().replace("°", "").replace("degrees", "").replace("degree", "");
        double result;
        if (isLength(from) && isLength(to)) {
            double meters = toMeters(value, from);
            result = fromMeters(meters, to);
        } else if (isWeight(from) && isWeight(to)) {
            double kg = toKg(value, from);
            result = fromKg(kg, to);
        } else if (isTemp(from) && isTemp(to)) {
            double celsius = toCelsius(value, from);
            result = fromCelsius(celsius, to);
        } else {
            return "Unsupported or mismatched units. Use: length (mile, km, foot, m), weight (lb, kg), temperature (celsius, fahrenheit).";
        }
        String out = result == (long) result ? String.valueOf((long) result) : String.format("%.4g", result);
        return value + " " + fromUnit + " = " + out + " " + toUnit;
    }

    private static boolean isLength(String u) {
        return u.equals("mile") || u.equals("miles") || u.equals("km") || u.equals("kilometer") || u.equals("kilometers")
                || u.equals("foot") || u.equals("feet") || u.equals("ft") || u.equals("m") || u.equals("meter") || u.equals("meters")
                || u.equals("inch") || u.equals("inches") || u.equals("in");
    }

    private static boolean isWeight(String u) {
        return u.equals("lb") || u.equals("lbs") || u.equals("pound") || u.equals("pounds")
                || u.equals("kg") || u.equals("kilogram") || u.equals("kilograms");
    }

    private static boolean isTemp(String u) {
        return u.equals("celsius") || u.equals("c") || u.equals("fahrenheit") || u.equals("f");
    }

    private static double toMeters(double v, String u) {
        return switch (u) {
            case "mile", "miles" -> v * 1609.344;
            case "km", "kilometer", "kilometers" -> v * 1000;
            case "foot", "feet", "ft" -> v * 0.3048;
            case "m", "meter", "meters" -> v;
            case "inch", "inches", "in" -> v * 0.0254;
            default -> throw new IllegalArgumentException("Unknown length: " + u);
        };
    }

    private static double fromMeters(double m, String u) {
        return switch (u) {
            case "mile", "miles" -> m / 1609.344;
            case "km", "kilometer", "kilometers" -> m / 1000;
            case "foot", "feet", "ft" -> m / 0.3048;
            case "m", "meter", "meters" -> m;
            case "inch", "inches", "in" -> m / 0.0254;
            default -> throw new IllegalArgumentException("Unknown length: " + u);
        };
    }

    private static double toKg(double v, String u) {
        if (u.startsWith("lb")) return v * 0.453592;
        if (u.startsWith("kg")) return v;
        throw new IllegalArgumentException("Unknown weight: " + u);
    }

    private static double fromKg(double kg, String u) {
        if (u.startsWith("lb")) return kg / 0.453592;
        if (u.startsWith("kg")) return kg;
        throw new IllegalArgumentException("Unknown weight: " + u);
    }

    private static double toCelsius(double v, String u) {
        if (u.startsWith("f")) return (v - 32) * 5 / 9;
        if (u.startsWith("c")) return v;
        throw new IllegalArgumentException("Unknown temp: " + u);
    }

    private static double fromCelsius(double c, String u) {
        if (u.startsWith("f")) return c * 9 / 5 + 32;
        if (u.startsWith("c")) return c;
        throw new IllegalArgumentException("Unknown temp: " + u);
    }
}
