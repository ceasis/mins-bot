package com.minsbot.skills.macrocalc;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class MacroCalcService {

    public Map<String, Object> computeFromCalories(double calories, String goal) {
        // kcal/g: protein 4, carbs 4, fat 9
        double proteinPct, carbPct, fatPct;
        switch (goal == null ? "balanced" : goal.toLowerCase()) {
            case "keto"          -> { proteinPct = 25; carbPct = 5;  fatPct = 70; }
            case "low-carb"      -> { proteinPct = 30; carbPct = 20; fatPct = 50; }
            case "cut", "fatloss"-> { proteinPct = 40; carbPct = 35; fatPct = 25; }
            case "bulk", "gain"  -> { proteinPct = 25; carbPct = 50; fatPct = 25; }
            case "endurance"     -> { proteinPct = 20; carbPct = 60; fatPct = 20; }
            default              -> { proteinPct = 30; carbPct = 40; fatPct = 30; } // balanced
        }
        double proteinG = calories * proteinPct / 100.0 / 4.0;
        double carbG = calories * carbPct / 100.0 / 4.0;
        double fatG = calories * fatPct / 100.0 / 9.0;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("calories", calories);
        out.put("goal", goal == null ? "balanced" : goal);
        out.put("split", Map.of("proteinPct", proteinPct, "carbPct", carbPct, "fatPct", fatPct));
        out.put("grams", Map.of("protein", round(proteinG), "carbs", round(carbG), "fat", round(fatG)));
        out.put("kcal", Map.of("protein", round(proteinG * 4), "carbs", round(carbG * 4), "fat", round(fatG * 9)));
        return out;
    }

    public Map<String, Object> computeFromWeight(double weightKg, double proteinPerKg, double fatPerKg, double remainingCaloriesToCarbs) {
        // Protein 1.6-2.2 g/kg, fat 0.8-1.2 g/kg, rest carbs. remainingCaloriesToCarbs is daily kcal target.
        double proteinG = weightKg * proteinPerKg;
        double fatG = weightKg * fatPerKg;
        double proteinKcal = proteinG * 4;
        double fatKcal = fatG * 9;
        double carbKcal = remainingCaloriesToCarbs - proteinKcal - fatKcal;
        double carbG = carbKcal / 4.0;
        if (carbG < 0) carbG = 0;

        return Map.of(
                "weightKg", weightKg,
                "targetKcal", remainingCaloriesToCarbs,
                "grams", Map.of("protein", round(proteinG), "carbs", round(carbG), "fat", round(fatG)),
                "kcal", Map.of("protein", round(proteinKcal), "carbs", round(Math.max(0, carbKcal)), "fat", round(fatKcal))
        );
    }

    private static double round(double v) { return Math.round(v * 10.0) / 10.0; }
}
