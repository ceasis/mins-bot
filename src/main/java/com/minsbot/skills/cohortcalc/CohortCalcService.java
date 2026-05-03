package com.minsbot.skills.cohortcalc;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Computes retention curve, average retention, and back-of-envelope LTV +
 * CAC payback months given ARPU and CAC.
 */
@Service
public class CohortCalcService {

    public Map<String, Object> compute(List<Map<String, Object>> cohorts,
                                       Double arpu, Double cac, Double grossMargin) {
        if (cohorts == null || cohorts.isEmpty()) throw new IllegalArgumentException("cohorts required");
        if (grossMargin == null) grossMargin = 0.7;

        // Each cohort: {periodLabel, signups, retainedByPeriod: [n0, n1, n2, ...]}
        int maxLen = 0;
        for (Map<String, Object> c : cohorts) {
            Object r = c.get("retainedByPeriod");
            if (r instanceof List<?> l) maxLen = Math.max(maxLen, l.size());
        }

        double[] avgRetention = new double[maxLen];
        int[] cohortsAtPeriod = new int[maxLen];
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> c : cohorts) {
            long signups = ((Number) c.getOrDefault("signups", 0)).longValue();
            List<?> retained = (List<?>) c.getOrDefault("retainedByPeriod", List.of());
            List<Double> rates = new ArrayList<>();
            for (int i = 0; i < retained.size(); i++) {
                long n = ((Number) retained.get(i)).longValue();
                double rate = signups == 0 ? 0 : (double) n / signups;
                rates.add(round(rate, 4));
                avgRetention[i] += rate;
                cohortsAtPeriod[i]++;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("periodLabel", c.get("periodLabel"));
            m.put("signups", signups);
            m.put("retentionRates", rates);
            normalized.add(m);
        }

        List<Double> avgCurve = new ArrayList<>();
        for (int i = 0; i < maxLen; i++) {
            avgCurve.add(cohortsAtPeriod[i] == 0 ? 0 : round(avgRetention[i] / cohortsAtPeriod[i], 4));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skill", "cohortcalc");
        result.put("cohorts", normalized);
        result.put("averageRetentionCurve", avgCurve);

        if (arpu != null) {
            // LTV = sum(arpu * retention[i] * grossMargin) over periods
            double ltv = 0;
            for (Double r : avgCurve) ltv += arpu * r * grossMargin;
            result.put("arpu", arpu);
            result.put("grossMargin", grossMargin);
            result.put("estimatedLtv", round(ltv, 2));
            if (cac != null && cac > 0) {
                double monthlyContribution = arpu * grossMargin;
                double payback = monthlyContribution == 0 ? -1 : cac / monthlyContribution;
                result.put("cac", cac);
                result.put("ltvCacRatio", round(ltv / cac, 2));
                result.put("paybackPeriods", payback < 0 ? "n/a" : round(payback, 1));
                result.put("verdict", ltv / cac >= 3 ? "Healthy (LTV:CAC >= 3)"
                        : ltv / cac >= 1 ? "Marginal — improve retention or reduce CAC"
                        : "Losing money on each customer");
            }
        }
        return result;
    }

    private static double round(double v, int p) {
        double f = Math.pow(10, p);
        return Math.round(v * f) / f;
    }
}
