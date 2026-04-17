package com.minsbot.skills.geometrycalc;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class GeometryCalcService {

    public Map<String, Object> shape2d(String shape, Map<String, Double> dims) {
        double area, perimeter;
        switch (shape.toLowerCase()) {
            case "square" -> { double s = need(dims, "side"); area = s * s; perimeter = 4 * s; }
            case "rectangle" -> { double w = need(dims, "width"), h = need(dims, "height"); area = w * h; perimeter = 2 * (w + h); }
            case "circle" -> { double r = need(dims, "radius"); area = Math.PI * r * r; perimeter = 2 * Math.PI * r; }
            case "triangle" -> {
                double b = need(dims, "base"), h = need(dims, "height");
                area = 0.5 * b * h;
                Double a = dims.get("sideA"), c = dims.get("sideB"), d = dims.get("sideC");
                perimeter = (a != null && c != null && d != null) ? a + c + d : Double.NaN;
            }
            case "trapezoid" -> { double a = need(dims, "a"), b = need(dims, "b"), h = need(dims, "height");
                area = 0.5 * (a + b) * h;
                Double l = dims.get("leftLeg"), r = dims.get("rightLeg");
                perimeter = (l != null && r != null) ? a + b + l + r : Double.NaN; }
            case "ellipse" -> { double a = need(dims, "semiMajor"), b = need(dims, "semiMinor");
                area = Math.PI * a * b;
                double h = Math.pow(a - b, 2) / Math.pow(a + b, 2);
                perimeter = Math.PI * (a + b) * (1 + 3 * h / (10 + Math.sqrt(4 - 3 * h))); }
            case "regular-polygon" -> {
                int n = need(dims, "sides").intValue();
                double s = need(dims, "sideLength");
                area = n * s * s / (4 * Math.tan(Math.PI / n));
                perimeter = n * s;
            }
            default -> throw new IllegalArgumentException("shape: square|rectangle|circle|triangle|trapezoid|ellipse|regular-polygon");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shape", shape);
        out.put("area", round(area));
        out.put("perimeter", Double.isNaN(perimeter) ? "requires additional dimensions" : round(perimeter));
        return out;
    }

    public Map<String, Object> shape3d(String shape, Map<String, Double> dims) {
        double volume, surface;
        switch (shape.toLowerCase()) {
            case "cube" -> { double s = need(dims, "side"); volume = s * s * s; surface = 6 * s * s; }
            case "box", "rect-prism" -> { double l = need(dims, "length"), w = need(dims, "width"), h = need(dims, "height");
                volume = l * w * h; surface = 2 * (l * w + w * h + l * h); }
            case "sphere" -> { double r = need(dims, "radius"); volume = 4.0 / 3.0 * Math.PI * r * r * r; surface = 4 * Math.PI * r * r; }
            case "cylinder" -> { double r = need(dims, "radius"), h = need(dims, "height");
                volume = Math.PI * r * r * h; surface = 2 * Math.PI * r * (r + h); }
            case "cone" -> { double r = need(dims, "radius"), h = need(dims, "height");
                volume = 1.0 / 3.0 * Math.PI * r * r * h;
                double slant = Math.sqrt(r * r + h * h);
                surface = Math.PI * r * (r + slant); }
            case "pyramid" -> { double b = need(dims, "baseEdge"), h = need(dims, "height");
                volume = (b * b * h) / 3.0;
                double slant = Math.sqrt((b / 2) * (b / 2) + h * h);
                surface = b * b + 2 * b * slant; }
            default -> throw new IllegalArgumentException("shape: cube|box|sphere|cylinder|cone|pyramid");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shape", shape);
        out.put("volume", round(volume));
        out.put("surfaceArea", round(surface));
        return out;
    }

    public Map<String, Object> distance(double x1, double y1, double x2, double y2) {
        double d = Math.sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1));
        return Map.of("distance", round(d), "deltaX", x2 - x1, "deltaY", y2 - y1);
    }

    private static Double need(Map<String, Double> m, String key) {
        Object v = m.get(key);
        if (v == null) throw new IllegalArgumentException("missing dimension: " + key);
        return ((Number) v).doubleValue();
    }

    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
