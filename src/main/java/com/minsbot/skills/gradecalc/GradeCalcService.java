package com.minsbot.skills.gradecalc;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class GradeCalcService {

    public Map<String, Object> weighted(List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) throw new IllegalArgumentException("items required");
        double totalWeight = 0, weightedSum = 0;
        List<Map<String, Object>> breakdown = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String name = String.valueOf(item.getOrDefault("name", "?"));
            double score = ((Number) item.get("score")).doubleValue();
            double weight = ((Number) item.get("weight")).doubleValue();
            totalWeight += weight;
            weightedSum += score * weight;
            breakdown.add(Map.of("name", name, "score", score, "weight", weight, "contribution", round(score * weight)));
        }
        if (totalWeight <= 0) throw new IllegalArgumentException("totalWeight must be > 0");
        double finalGrade = weightedSum / totalWeight;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("itemCount", items.size());
        out.put("totalWeight", round(totalWeight));
        out.put("finalGrade", round(finalGrade));
        out.put("letterGrade", letter(finalGrade));
        out.put("breakdown", breakdown);
        return out;
    }

    public Map<String, Object> needed(double currentGrade, double currentWeight, double targetGrade, double remainingWeight) {
        if (remainingWeight <= 0) throw new IllegalArgumentException("remainingWeight > 0");
        double needed = (targetGrade * (currentWeight + remainingWeight) - currentGrade * currentWeight) / remainingWeight;
        return Map.of(
                "currentGrade", currentGrade,
                "currentWeight", currentWeight,
                "targetGrade", targetGrade,
                "remainingWeight", remainingWeight,
                "scoreNeededOnRemainder", round(needed),
                "feasible", needed <= 100 && needed >= 0
        );
    }

    public Map<String, Object> gpa(List<Map<String, Object>> courses, String scale) {
        double totalPoints = 0, totalCredits = 0;
        for (Map<String, Object> c : courses) {
            double credits = ((Number) c.get("credits")).doubleValue();
            double points;
            if (c.get("gradePoints") != null) points = ((Number) c.get("gradePoints")).doubleValue();
            else points = letterToPoints(String.valueOf(c.get("letter")), scale);
            totalPoints += points * credits;
            totalCredits += credits;
        }
        double gpa = totalCredits == 0 ? 0 : totalPoints / totalCredits;
        return Map.of("courses", courses.size(), "totalCredits", totalCredits, "gpa", round(gpa), "scale", scale);
    }

    private static String letter(double g) {
        if (g >= 97) return "A+"; if (g >= 93) return "A"; if (g >= 90) return "A-";
        if (g >= 87) return "B+"; if (g >= 83) return "B"; if (g >= 80) return "B-";
        if (g >= 77) return "C+"; if (g >= 73) return "C"; if (g >= 70) return "C-";
        if (g >= 67) return "D+"; if (g >= 63) return "D"; if (g >= 60) return "D-";
        return "F";
    }

    private static double letterToPoints(String letter, String scale) {
        boolean four = "4.0".equals(scale);
        return switch (letter.toUpperCase()) {
            case "A+", "A" -> four ? 4.0 : 4.3;
            case "A-" -> 3.7;
            case "B+" -> 3.3;
            case "B" -> 3.0;
            case "B-" -> 2.7;
            case "C+" -> 2.3;
            case "C" -> 2.0;
            case "C-" -> 1.7;
            case "D+" -> 1.3;
            case "D" -> 1.0;
            case "D-" -> 0.7;
            default -> 0.0;
        };
    }

    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
