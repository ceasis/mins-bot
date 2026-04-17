package com.minsbot.skills.realestatecalc;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class RealEstateCalcService {

    public Map<String, Object> mortgage(double price, double downPayment, double annualRatePct, int termYears,
                                        Double annualPropertyTax, Double annualInsurance, Double monthlyHoa) {
        if (price <= 0 || downPayment < 0 || downPayment > price) throw new IllegalArgumentException("invalid price or down payment");
        double loan = price - downPayment;
        int n = termYears * 12;
        double r = annualRatePct / 100.0 / 12;
        double piPayment = r == 0 ? loan / n : loan * r / (1 - Math.pow(1 + r, -n));
        double taxMonthly = annualPropertyTax == null ? 0 : annualPropertyTax / 12;
        double insMonthly = annualInsurance == null ? 0 : annualInsurance / 12;
        double hoaMonthly = monthlyHoa == null ? 0 : monthlyHoa;
        double totalMonthly = piPayment + taxMonthly + insMonthly + hoaMonthly;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("price", price);
        out.put("downPayment", downPayment);
        out.put("downPaymentPct", round(downPayment / price * 100));
        out.put("loanAmount", round(loan));
        out.put("annualRatePct", annualRatePct);
        out.put("termYears", termYears);
        out.put("principalInterestMonthly", round(piPayment));
        out.put("taxMonthly", round(taxMonthly));
        out.put("insuranceMonthly", round(insMonthly));
        out.put("hoaMonthly", round(hoaMonthly));
        out.put("totalMonthlyPITI", round(totalMonthly));
        out.put("totalInterestOverLife", round(piPayment * n - loan));
        out.put("totalPaidOverLife", round(piPayment * n));
        return out;
    }

    public Map<String, Object> capRate(double annualNetOperatingIncome, double propertyValue) {
        if (propertyValue <= 0) throw new IllegalArgumentException("propertyValue > 0");
        double cap = annualNetOperatingIncome / propertyValue * 100;
        return Map.of("noi", annualNetOperatingIncome, "propertyValue", propertyValue, "capRatePct", round(cap));
    }

    public Map<String, Object> cashOnCash(double annualCashFlow, double totalCashInvested) {
        if (totalCashInvested <= 0) throw new IllegalArgumentException("totalCashInvested > 0");
        double coc = annualCashFlow / totalCashInvested * 100;
        return Map.of("annualCashFlow", annualCashFlow, "totalCashInvested", totalCashInvested, "cashOnCashReturnPct", round(coc));
    }

    public Map<String, Object> one_percent_rule(double monthlyRent, double purchasePrice) {
        if (purchasePrice <= 0) throw new IllegalArgumentException("purchasePrice > 0");
        double ratio = monthlyRent / purchasePrice * 100;
        return Map.of(
                "monthlyRent", monthlyRent,
                "purchasePrice", purchasePrice,
                "rentToPricePct", round(ratio),
                "passes1PercentRule", ratio >= 1.0
        );
    }

    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
