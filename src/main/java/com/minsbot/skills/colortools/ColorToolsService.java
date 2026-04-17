package com.minsbot.skills.colortools;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ColorToolsService {

    public Map<String, Object> parse(String input) {
        int[] rgb = parseColor(input);
        double[] hsl = rgbToHsl(rgb[0], rgb[1], rgb[2]);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hex", hex(rgb));
        out.put("rgb", Map.of("r", rgb[0], "g", rgb[1], "b", rgb[2]));
        out.put("hsl", Map.of("h", (int) Math.round(hsl[0]), "s", (int) Math.round(hsl[1]), "l", (int) Math.round(hsl[2])));
        out.put("luminance", relativeLuminance(rgb));
        return out;
    }

    public Map<String, Object> contrast(String fg, String bg) {
        int[] f = parseColor(fg), b = parseColor(bg);
        double lf = relativeLuminance(f), lb = relativeLuminance(b);
        double lighter = Math.max(lf, lb), darker = Math.min(lf, lb);
        double ratio = (lighter + 0.05) / (darker + 0.05);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fg", hex(f));
        out.put("bg", hex(b));
        out.put("ratio", Math.round(ratio * 100.0) / 100.0);
        out.put("wcag_AA_normal", ratio >= 4.5);
        out.put("wcag_AA_large", ratio >= 3.0);
        out.put("wcag_AAA_normal", ratio >= 7.0);
        out.put("wcag_AAA_large", ratio >= 4.5);
        return out;
    }

    public Map<String, Object> palette(String baseHex, String scheme) {
        int[] rgb = parseColor(baseHex);
        double[] hsl = rgbToHsl(rgb[0], rgb[1], rgb[2]);
        double h = hsl[0], s = hsl[1], l = hsl[2];
        List<String> colors = new ArrayList<>();
        switch (scheme.toLowerCase()) {
            case "complementary" -> { colors.add(hex(rgb)); colors.add(hslToHex((h + 180) % 360, s, l)); }
            case "triadic" -> { colors.add(hex(rgb)); colors.add(hslToHex((h + 120) % 360, s, l)); colors.add(hslToHex((h + 240) % 360, s, l)); }
            case "analogous" -> { colors.add(hslToHex((h - 30 + 360) % 360, s, l)); colors.add(hex(rgb)); colors.add(hslToHex((h + 30) % 360, s, l)); }
            case "tetradic" -> { colors.add(hex(rgb)); colors.add(hslToHex((h + 90) % 360, s, l)); colors.add(hslToHex((h + 180) % 360, s, l)); colors.add(hslToHex((h + 270) % 360, s, l)); }
            case "monochromatic" -> {
                for (double step : new double[]{-30, -15, 0, 15, 30}) {
                    double nl = Math.max(0, Math.min(100, l + step));
                    colors.add(hslToHex(h, s, nl));
                }
            }
            default -> throw new IllegalArgumentException("Unknown scheme: " + scheme);
        }
        return Map.of("base", hex(rgb), "scheme", scheme, "colors", colors);
    }

    private static int[] parseColor(String input) {
        if (input == null) throw new IllegalArgumentException("color required");
        String s = input.trim().toLowerCase();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.matches("[0-9a-f]{3}")) {
            s = "" + s.charAt(0) + s.charAt(0) + s.charAt(1) + s.charAt(1) + s.charAt(2) + s.charAt(2);
        }
        if (s.matches("[0-9a-f]{6}")) {
            return new int[]{Integer.parseInt(s.substring(0, 2), 16), Integer.parseInt(s.substring(2, 4), 16), Integer.parseInt(s.substring(4, 6), 16)};
        }
        if (s.startsWith("rgb")) {
            String[] parts = s.replaceAll("[^0-9,]", "").split(",");
            if (parts.length >= 3) return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
        }
        throw new IllegalArgumentException("Unrecognized color format: " + input);
    }

    private static String hex(int[] rgb) {
        return String.format("#%02x%02x%02x", rgb[0], rgb[1], rgb[2]);
    }

    private static double relativeLuminance(int[] rgb) {
        double[] lin = new double[3];
        for (int i = 0; i < 3; i++) {
            double c = rgb[i] / 255.0;
            lin[i] = c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2];
    }

    private static double[] rgbToHsl(int r, int g, int b) {
        double rn = r / 255.0, gn = g / 255.0, bn = b / 255.0;
        double max = Math.max(rn, Math.max(gn, bn)), min = Math.min(rn, Math.min(gn, bn));
        double l = (max + min) / 2, h = 0, s = 0;
        if (max != min) {
            double d = max - min;
            s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
            if (max == rn) h = (gn - bn) / d + (gn < bn ? 6 : 0);
            else if (max == gn) h = (bn - rn) / d + 2;
            else h = (rn - gn) / d + 4;
            h *= 60;
        }
        return new double[]{h, s * 100, l * 100};
    }

    private static String hslToHex(double h, double s, double l) {
        s /= 100; l /= 100;
        double c = (1 - Math.abs(2 * l - 1)) * s;
        double x = c * (1 - Math.abs((h / 60) % 2 - 1));
        double m = l - c / 2;
        double r1 = 0, g1 = 0, b1 = 0;
        if (h < 60) { r1 = c; g1 = x; }
        else if (h < 120) { r1 = x; g1 = c; }
        else if (h < 180) { g1 = c; b1 = x; }
        else if (h < 240) { g1 = x; b1 = c; }
        else if (h < 300) { r1 = x; b1 = c; }
        else { r1 = c; b1 = x; }
        return String.format("#%02x%02x%02x",
                (int) Math.round((r1 + m) * 255), (int) Math.round((g1 + m) * 255), (int) Math.round((b1 + m) * 255));
    }
}
