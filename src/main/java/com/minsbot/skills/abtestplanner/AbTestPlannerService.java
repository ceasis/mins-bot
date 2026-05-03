package com.minsbot.skills.abtestplanner;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * A/B test planning: required sample size per variant, days-to-significance
 * given daily traffic. Uses two-proportion z-test approximation at alpha=0.05.
 */
@Service
public class AbTestPlannerService {

    public Map<String, Object> plan(double baselineRate, double mdeRelative,
                                    double dailyTraffic, int numVariants) {
        if (baselineRate <= 0 || baselineRate >= 1) throw new IllegalArgumentException("baselineRate must be in (0,1)");
        if (mdeRelative <= 0) throw new IllegalArgumentException("mdeRelative must be > 0");
        if (numVariants < 2) numVariants = 2;

        double p1 = baselineRate;
        double p2 = baselineRate * (1 + mdeRelative);
        double pBar = (p1 + p2) / 2;
        // Two-proportion sample size, alpha=0.05 (z=1.96), power=0.80 (z=0.84)
        double zA = 1.96;
        double zB = 0.84;
        double numerator = Math.pow(zA * Math.sqrt(2 * pBar * (1 - pBar))
                + zB * Math.sqrt(p1 * (1 - p1) + p2 * (1 - p2)), 2);
        double denom = Math.pow(p2 - p1, 2);
        long perVariant = (long) Math.ceil(numerator / denom);
        long total = perVariant * numVariants;

        double perVariantTraffic = dailyTraffic / numVariants;
        double daysNeeded = perVariantTraffic > 0 ? perVariant / perVariantTraffic : -1;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skill", "abtestplanner");
        result.put("baselineRate", p1);
        result.put("expectedTreatmentRate", round(p2, 4));
        result.put("mdeRelative", mdeRelative);
        result.put("absoluteLift", round(p2 - p1, 4));
        result.put("numVariants", numVariants);
        result.put("alpha", 0.05);
        result.put("power", 0.80);
        result.put("samplePerVariant", perVariant);
        result.put("totalSampleNeeded", total);
        result.put("dailyTraffic", dailyTraffic);
        result.put("estimatedDaysToSignificance", daysNeeded < 0 ? "n/a" : Math.ceil(daysNeeded));
        result.put("warning", perVariant < 100
                ? "Sample is small — consider running longer to capture day-of-week effects."
                : null);
        return result;
    }

    public Map<String, Object> evaluate(long aN, long aConv, long bN, long bConv) {
        if (aN <= 0 || bN <= 0) throw new IllegalArgumentException("sample sizes must be > 0");
        double pA = (double) aConv / aN;
        double pB = (double) bConv / bN;
        double pPool = (double) (aConv + bConv) / (aN + bN);
        double se = Math.sqrt(pPool * (1 - pPool) * (1.0 / aN + 1.0 / bN));
        double z = se == 0 ? 0 : (pB - pA) / se;
        double pValue = 2 * (1 - normalCdf(Math.abs(z)));
        boolean significant = pValue < 0.05;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("aRate", round(pA, 4));
        result.put("bRate", round(pB, 4));
        result.put("absoluteLift", round(pB - pA, 4));
        result.put("relativeLift", pA == 0 ? null : round((pB - pA) / pA, 4));
        result.put("zScore", round(z, 3));
        result.put("pValue", round(pValue, 4));
        result.put("significantAt95", significant);
        result.put("verdict", significant ? (pB > pA ? "B wins" : "A wins") : "Not yet significant — keep running");
        return result;
    }

    private static double round(double v, int p) {
        double f = Math.pow(10, p);
        return Math.round(v * f) / f;
    }

    private static double normalCdf(double x) {
        // Abramowitz approximation
        double a1 = 0.254829592, a2 = -0.284496736, a3 = 1.421413741;
        double a4 = -1.453152027, a5 = 1.061405429, p = 0.3275911;
        int sign = x < 0 ? -1 : 1;
        x = Math.abs(x) / Math.sqrt(2);
        double t = 1.0 / (1.0 + p * x);
        double y = 1.0 - (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1) * t * Math.exp(-x * x);
        return 0.5 * (1.0 + sign * y);
    }
}
