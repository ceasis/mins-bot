package com.minsbot.skills.pricingadvisor;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Suggests 3 pricing tiers given competitor anchors + your cost floor.
 * Uses Good/Better/Best (Van Westendorp-inspired) anchoring.
 */
@Service
public class PricingAdvisorService {

    public Map<String, Object> recommend(List<Double> competitorPrices, Double costFloor,
                                         Double targetMargin, String positioning) {
        if (competitorPrices == null) competitorPrices = List.of();
        if (costFloor == null) costFloor = 0.0;
        if (targetMargin == null) targetMargin = 0.5; // 50%
        if (positioning == null) positioning = "mid"; // budget | mid | premium

        double median = median(competitorPrices);
        double low = competitorPrices.isEmpty() ? costFloor * (1 + targetMargin)
                : Math.max(percentile(competitorPrices, 25), costFloor * (1 + targetMargin));
        double high = competitorPrices.isEmpty() ? low * 2.5 : percentile(competitorPrices, 75) * 1.15;

        double midAnchor = switch (positioning) {
            case "budget" -> low * 0.95;
            case "premium" -> high * 0.95;
            default -> median == 0 ? (low + high) / 2 : median;
        };

        double good = round(Math.max(costFloor * (1 + targetMargin), midAnchor * 0.55));
        double better = round(midAnchor);
        double best = round(midAnchor * 1.85);

        List<Map<String, Object>> tiers = new ArrayList<>();
        tiers.add(tier("Good", good, "Entry tier — attracts price-sensitive buyers, anchors decoy",
                "Core deliverable, 1 revision, email support"));
        tiers.add(tier("Better", better, "The anchor — design your messaging to push buyers here",
                "Everything in Good + 3 revisions + priority support + 1 strategy call"));
        tiers.add(tier("Best", best, "Premium tier — frees you to negotiate down to Better",
                "Everything in Better + unlimited revisions + dedicated channel + done-for-you setup"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skill", "pricingadvisor");
        result.put("competitorMedian", round(median));
        result.put("competitorP25", round(percentile(competitorPrices, 25)));
        result.put("competitorP75", round(percentile(competitorPrices, 75)));
        result.put("costFloor", costFloor);
        result.put("targetMargin", targetMargin);
        result.put("positioning", positioning);
        result.put("tiers", tiers);
        result.put("rationale", "Three-tier (decoy) pricing increases AOV ~18-30% vs single-price by anchoring high.");
        return result;
    }

    private static Map<String, Object> tier(String name, double price, String why, String includes) {
        return Map.of("name", name, "price", price, "rationale", why, "includes", includes);
    }

    private static double median(List<Double> xs) { return percentile(xs, 50); }

    private static double percentile(List<Double> xs, double p) {
        if (xs.isEmpty()) return 0;
        List<Double> sorted = new ArrayList<>(xs);
        Collections.sort(sorted);
        int idx = (int) Math.min(sorted.size() - 1, Math.max(0, Math.round((p / 100.0) * (sorted.size() - 1))));
        return sorted.get(idx);
    }

    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
