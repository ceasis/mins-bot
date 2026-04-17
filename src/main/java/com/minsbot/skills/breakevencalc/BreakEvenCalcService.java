package com.minsbot.skills.breakevencalc;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class BreakEvenCalcService {

    public Map<String, Object> compute(double fixedCosts, double pricePerUnit, double variableCostPerUnit) {
        if (pricePerUnit <= variableCostPerUnit) {
            throw new IllegalArgumentException("pricePerUnit must be > variableCostPerUnit (no margin)");
        }
        double contribution = pricePerUnit - variableCostPerUnit;
        double breakEvenUnits = fixedCosts / contribution;
        double breakEvenRevenue = breakEvenUnits * pricePerUnit;
        double cmRatio = contribution / pricePerUnit;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fixedCosts", fixedCosts);
        out.put("pricePerUnit", pricePerUnit);
        out.put("variableCostPerUnit", variableCostPerUnit);
        out.put("contributionMarginPerUnit", round(contribution));
        out.put("contributionMarginRatio", round(cmRatio));
        out.put("breakEvenUnits", Math.ceil(breakEvenUnits));
        out.put("breakEvenRevenue", round(breakEvenRevenue));
        return out;
    }

    public Map<String, Object> targetProfit(double fixedCosts, double pricePerUnit, double variableCostPerUnit, double targetProfit) {
        double contribution = pricePerUnit - variableCostPerUnit;
        if (contribution <= 0) throw new IllegalArgumentException("price must be > variable cost");
        double units = (fixedCosts + targetProfit) / contribution;
        return Map.of("targetProfit", targetProfit, "unitsRequired", Math.ceil(units), "revenueRequired", round(Math.ceil(units) * pricePerUnit));
    }

    public Map<String, Object> safetyMargin(double actualRevenue, double breakEvenRevenue) {
        if (actualRevenue <= 0) throw new IllegalArgumentException("actualRevenue > 0");
        double marginPct = (actualRevenue - breakEvenRevenue) / actualRevenue * 100.0;
        return Map.of("actualRevenue", actualRevenue, "breakEvenRevenue", breakEvenRevenue, "marginOfSafetyPercent", round(marginPct), "aboveBreakEven", actualRevenue > breakEvenRevenue);
    }

    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
