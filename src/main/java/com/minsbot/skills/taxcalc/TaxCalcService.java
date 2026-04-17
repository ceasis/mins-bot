package com.minsbot.skills.taxcalc;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class TaxCalcService {

    public static class Bracket {
        public double upperLimit;
        public double ratePct;
    }

    public Map<String, Object> computeBrackets(double income, List<Map<String, Object>> bracketList) {
        if (income < 0) throw new IllegalArgumentException("income must be >= 0");
        if (bracketList == null || bracketList.isEmpty()) throw new IllegalArgumentException("brackets required");

        List<Bracket> brackets = new ArrayList<>();
        for (Map<String, Object> b : bracketList) {
            Bracket br = new Bracket();
            Object upper = b.get("upperLimit");
            br.upperLimit = upper == null ? Double.MAX_VALUE : ((Number) upper).doubleValue();
            br.ratePct = ((Number) b.get("ratePct")).doubleValue();
            brackets.add(br);
        }
        brackets.sort(Comparator.comparingDouble(b -> b.upperLimit));

        double remaining = income;
        double prevUpper = 0;
        double totalTax = 0;
        List<Map<String, Object>> breakdown = new ArrayList<>();

        for (Bracket b : brackets) {
            if (remaining <= 0) break;
            double bracketSize = b.upperLimit - prevUpper;
            double taxableInThisBracket = Math.min(remaining, bracketSize);
            double taxHere = taxableInThisBracket * b.ratePct / 100.0;
            totalTax += taxHere;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("from", round(prevUpper));
            entry.put("to", round(b.upperLimit));
            entry.put("ratePct", b.ratePct);
            entry.put("taxable", round(taxableInThisBracket));
            entry.put("tax", round(taxHere));
            breakdown.add(entry);
            remaining -= taxableInThisBracket;
            prevUpper = b.upperLimit;
        }

        double effectiveRate = income == 0 ? 0 : totalTax / income * 100.0;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("income", income);
        result.put("totalTax", round(totalTax));
        result.put("netIncome", round(income - totalTax));
        result.put("effectiveRatePct", round(effectiveRate));
        result.put("marginalRatePct", marginalRate(income, brackets));
        result.put("breakdown", breakdown);
        return result;
    }

    private static double marginalRate(double income, List<Bracket> brackets) {
        double prev = 0;
        for (Bracket b : brackets) {
            if (income <= b.upperLimit) return b.ratePct;
            prev = b.upperLimit;
        }
        return brackets.get(brackets.size() - 1).ratePct;
    }

    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
