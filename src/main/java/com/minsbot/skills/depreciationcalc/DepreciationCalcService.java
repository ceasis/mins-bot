package com.minsbot.skills.depreciationcalc;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class DepreciationCalcService {

    public Map<String, Object> straightLine(double cost, double salvage, int usefulYears) {
        if (usefulYears <= 0) throw new IllegalArgumentException("usefulYears > 0");
        double annual = (cost - salvage) / usefulYears;
        List<Map<String, Object>> schedule = new ArrayList<>();
        double book = cost;
        for (int y = 1; y <= usefulYears; y++) {
            book -= annual;
            schedule.add(Map.of("year", y, "depreciation", round(annual), "bookValue", round(book)));
        }
        return Map.of("method", "straight-line", "annualDepreciation", round(annual), "schedule", schedule);
    }

    public Map<String, Object> decliningBalance(double cost, double salvage, int usefulYears, double rateMultiplier) {
        if (usefulYears <= 0) throw new IllegalArgumentException("usefulYears > 0");
        double rate = rateMultiplier / usefulYears;
        List<Map<String, Object>> schedule = new ArrayList<>();
        double book = cost;
        for (int y = 1; y <= usefulYears; y++) {
            double dep = Math.min(book * rate, book - salvage);
            if (dep < 0) dep = 0;
            book -= dep;
            schedule.add(Map.of("year", y, "depreciation", round(dep), "bookValue", round(book)));
        }
        return Map.of("method", rateMultiplier == 2 ? "double-declining-balance" : "declining-balance", "rate", round(rate), "schedule", schedule);
    }

    public Map<String, Object> sumOfYearsDigits(double cost, double salvage, int usefulYears) {
        if (usefulYears <= 0) throw new IllegalArgumentException("usefulYears > 0");
        int soyd = usefulYears * (usefulYears + 1) / 2;
        double depreciable = cost - salvage;
        List<Map<String, Object>> schedule = new ArrayList<>();
        double book = cost;
        for (int y = 1; y <= usefulYears; y++) {
            double fraction = (usefulYears - y + 1) / (double) soyd;
            double dep = depreciable * fraction;
            book -= dep;
            schedule.add(Map.of("year", y, "depreciation", round(dep), "bookValue", round(book)));
        }
        return Map.of("method", "sum-of-years-digits", "sumOfYears", soyd, "schedule", schedule);
    }

    public Map<String, Object> unitsOfProduction(double cost, double salvage, double totalExpectedUnits, List<Integer> unitsPerYear) {
        if (totalExpectedUnits <= 0 || unitsPerYear == null || unitsPerYear.isEmpty()) throw new IllegalArgumentException("invalid inputs");
        double perUnit = (cost - salvage) / totalExpectedUnits;
        List<Map<String, Object>> schedule = new ArrayList<>();
        double book = cost;
        for (int y = 0; y < unitsPerYear.size(); y++) {
            double dep = unitsPerYear.get(y) * perUnit;
            book -= dep;
            schedule.add(Map.of("year", y + 1, "units", unitsPerYear.get(y), "depreciation", round(dep), "bookValue", round(book)));
        }
        return Map.of("method", "units-of-production", "depreciationPerUnit", round(perUnit), "schedule", schedule);
    }

    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
