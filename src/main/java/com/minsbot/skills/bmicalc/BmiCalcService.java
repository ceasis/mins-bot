package com.minsbot.skills.bmicalc;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class BmiCalcService {

    public Map<String, Object> bmi(double weightKg, double heightCm) {
        if (weightKg <= 0 || heightCm <= 0) throw new IllegalArgumentException("weight and height required");
        double heightM = heightCm / 100.0;
        double bmi = weightKg / (heightM * heightM);
        String category;
        if (bmi < 18.5) category = "Underweight";
        else if (bmi < 25) category = "Normal";
        else if (bmi < 30) category = "Overweight";
        else if (bmi < 35) category = "Obese I";
        else if (bmi < 40) category = "Obese II";
        else category = "Obese III";

        double idealMin = 18.5 * heightM * heightM;
        double idealMax = 24.9 * heightM * heightM;
        return Map.of(
                "weightKg", weightKg,
                "heightCm", heightCm,
                "bmi", round(bmi),
                "category", category,
                "idealWeightRangeKg", Map.of("min", round(idealMin), "max", round(idealMax))
        );
    }

    public Map<String, Object> bmr(double weightKg, double heightCm, int age, String sex) {
        if (weightKg <= 0 || heightCm <= 0 || age <= 0) throw new IllegalArgumentException("weight/height/age required");
        double bmr;
        if ("male".equalsIgnoreCase(sex) || "m".equalsIgnoreCase(sex)) {
            bmr = 10 * weightKg + 6.25 * heightCm - 5 * age + 5;
        } else {
            bmr = 10 * weightKg + 6.25 * heightCm - 5 * age - 161;
        }
        return Map.of("weightKg", weightKg, "heightCm", heightCm, "age", age, "sex", sex, "bmrKcal", round(bmr));
    }

    public Map<String, Object> tdee(double weightKg, double heightCm, int age, String sex, String activityLevel) {
        double bmrVal = (double) bmr(weightKg, heightCm, age, sex).get("bmrKcal");
        double multiplier = switch (activityLevel.toLowerCase()) {
            case "sedentary" -> 1.2;
            case "light" -> 1.375;
            case "moderate" -> 1.55;
            case "active" -> 1.725;
            case "very-active" -> 1.9;
            default -> throw new IllegalArgumentException("activityLevel: sedentary|light|moderate|active|very-active");
        };
        double tdee = bmrVal * multiplier;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bmrKcal", bmrVal);
        out.put("activityLevel", activityLevel);
        out.put("multiplier", multiplier);
        out.put("tdeeKcal", round(tdee));
        out.put("targets", Map.of(
                "loseHalfKgPerWeek", round(tdee - 500),
                "loseOneKgPerWeek", round(tdee - 1000),
                "gainHalfKgPerWeek", round(tdee + 500),
                "gainOneKgPerWeek", round(tdee + 1000)
        ));
        return out;
    }

    public Map<String, Object> bodyFatNavy(String sex, double heightCm, double neckCm, double waistCm, Double hipCm) {
        double bf;
        if ("male".equalsIgnoreCase(sex) || "m".equalsIgnoreCase(sex)) {
            bf = 495 / (1.0324 - 0.19077 * Math.log10(waistCm - neckCm) + 0.15456 * Math.log10(heightCm)) - 450;
        } else {
            if (hipCm == null) throw new IllegalArgumentException("hipCm required for female");
            bf = 495 / (1.29579 - 0.35004 * Math.log10(waistCm + hipCm - neckCm) + 0.22100 * Math.log10(heightCm)) - 450;
        }
        return Map.of("bodyFatPct", round(bf), "method", "US Navy tape");
    }

    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
