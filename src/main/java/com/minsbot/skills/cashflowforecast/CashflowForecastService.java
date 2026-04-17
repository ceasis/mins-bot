package com.minsbot.skills.cashflowforecast;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class CashflowForecastService {

    public Map<String, Object> project(double openingBalance, double monthlyInflow, double monthlyOutflow,
                                       double inflowGrowthPct, double outflowGrowthPct, int months) {
        if (months <= 0 || months > 120) throw new IllegalArgumentException("months 1-120");
        List<Map<String, Object>> rows = new ArrayList<>();
        double balance = openingBalance;
        double inflow = monthlyInflow;
        double outflow = monthlyOutflow;
        int firstNegative = -1;
        double totalInflow = 0, totalOutflow = 0;
        for (int m = 1; m <= months; m++) {
            double net = inflow - outflow;
            balance += net;
            totalInflow += inflow;
            totalOutflow += outflow;
            if (firstNegative < 0 && balance < 0) firstNegative = m;
            rows.add(Map.of(
                    "month", m,
                    "inflow", round(inflow),
                    "outflow", round(outflow),
                    "netCashFlow", round(net),
                    "endingBalance", round(balance)
            ));
            inflow *= (1 + inflowGrowthPct / 100.0);
            outflow *= (1 + outflowGrowthPct / 100.0);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("openingBalance", openingBalance);
        out.put("totalInflow", round(totalInflow));
        out.put("totalOutflow", round(totalOutflow));
        out.put("netCashFlow", round(totalInflow - totalOutflow));
        out.put("endingBalance", round(balance));
        out.put("firstNegativeMonth", firstNegative < 0 ? null : firstNegative);
        out.put("projection", rows);
        return out;
    }

    public Map<String, Object> runway(double cash, double monthlyBurn) {
        if (monthlyBurn <= 0) return Map.of("cash", cash, "monthlyBurn", monthlyBurn, "runwayMonths", "infinite (no burn)");
        double months = cash / monthlyBurn;
        return Map.of("cash", cash, "monthlyBurn", monthlyBurn, "runwayMonths", round(months));
    }

    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
