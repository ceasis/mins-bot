package com.minsbot.skills.financecalc;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class FinanceCalcService {

    public Map<String, Object> compound(double principal, double annualRatePct, int years, int compoundsPerYear) {
        if (principal < 0 || annualRatePct < 0 || years <= 0 || compoundsPerYear <= 0) {
            throw new IllegalArgumentException("invalid parameters");
        }
        double r = annualRatePct / 100.0;
        double a = principal * Math.pow(1 + r / compoundsPerYear, (double) compoundsPerYear * years);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("principal", principal);
        out.put("annualRatePct", annualRatePct);
        out.put("years", years);
        out.put("compoundsPerYear", compoundsPerYear);
        out.put("finalAmount", round(a));
        out.put("interestEarned", round(a - principal));
        return out;
    }

    public Map<String, Object> loanPayment(double principal, double annualRatePct, int termYears) {
        if (principal <= 0 || annualRatePct < 0 || termYears <= 0) throw new IllegalArgumentException("invalid parameters");
        int n = termYears * 12;
        double r = annualRatePct / 100.0 / 12;
        double payment = r == 0 ? principal / n : principal * r / (1 - Math.pow(1 + r, -n));
        double total = payment * n;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("principal", principal);
        out.put("annualRatePct", annualRatePct);
        out.put("termYears", termYears);
        out.put("monthlyPayment", round(payment));
        out.put("totalPaid", round(total));
        out.put("totalInterest", round(total - principal));
        return out;
    }

    public Map<String, Object> npv(double discountRatePct, List<Double> cashFlows) {
        if (cashFlows == null || cashFlows.isEmpty()) throw new IllegalArgumentException("cashFlows required");
        double r = discountRatePct / 100.0;
        double npv = 0;
        for (int t = 0; t < cashFlows.size(); t++) {
            npv += cashFlows.get(t) / Math.pow(1 + r, t);
        }
        return Map.of("discountRatePct", discountRatePct, "npv", round(npv));
    }

    public Map<String, Object> irr(List<Double> cashFlows) {
        if (cashFlows == null || cashFlows.size() < 2) throw new IllegalArgumentException("need at least 2 cash flows");
        double low = -0.9999, high = 10.0;
        for (int i = 0; i < 200; i++) {
            double mid = (low + high) / 2;
            double v = 0;
            for (int t = 0; t < cashFlows.size(); t++) v += cashFlows.get(t) / Math.pow(1 + mid, t);
            if (Math.abs(v) < 1e-9) return Map.of("irrPct", round(mid * 100));
            if (v > 0) low = mid; else high = mid;
        }
        return Map.of("irrPct", round((low + high) / 2 * 100), "converged", false);
    }

    public Map<String, Object> roi(double gain, double cost) {
        if (cost == 0) throw new IllegalArgumentException("cost cannot be zero");
        double roiPct = (gain - cost) / cost * 100.0;
        return Map.of("gain", gain, "cost", cost, "netReturn", round(gain - cost), "roiPercent", round(roiPct));
    }

    public Map<String, Object> presentValue(double futureValue, double annualRatePct, int years) {
        double r = annualRatePct / 100.0;
        double pv = futureValue / Math.pow(1 + r, years);
        return Map.of("futureValue", futureValue, "annualRatePct", annualRatePct, "years", years, "presentValue", round(pv));
    }

    public Map<String, Object> futureValue(double presentValue, double annualRatePct, int years) {
        double r = annualRatePct / 100.0;
        double fv = presentValue * Math.pow(1 + r, years);
        return Map.of("presentValue", presentValue, "annualRatePct", annualRatePct, "years", years, "futureValue", round(fv));
    }

    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
