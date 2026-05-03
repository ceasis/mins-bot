package com.minsbot.skills.funnelanalyzer;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Computes per-stage drop-off, cumulative conversion, identifies the biggest leak.
 * Suggests an experiment per leaky stage.
 */
@Service
public class FunnelAnalyzerService {

    private static final Map<String, String> EXPERIMENTS = Map.ofEntries(
            Map.entry("landing", "Test new H1 + simpler CTA above-fold"),
            Map.entry("signup", "Reduce form fields; add SSO; show value before asking for email"),
            Map.entry("activation", "Add 2-min onboarding tour; pre-fill demo data"),
            Map.entry("first action", "Show clearer 'aha moment' early; add empty-state nudge"),
            Map.entry("payment", "Show price anchor; add trust badges; offer monthly toggle"),
            Map.entry("retention", "Send day-1 + day-7 win-back email; in-app re-engagement")
    );

    public Map<String, Object> analyze(List<Map<String, Object>> stages) {
        if (stages == null || stages.size() < 2)
            throw new IllegalArgumentException("Need at least 2 stages");

        long top = ((Number) stages.get(0).getOrDefault("count", 0)).longValue();
        if (top <= 0) throw new IllegalArgumentException("Top-of-funnel count must be > 0");

        List<Map<String, Object>> rows = new ArrayList<>();
        double biggestDropPct = 0;
        String leakStage = null;

        for (int i = 0; i < stages.size(); i++) {
            Map<String, Object> s = stages.get(i);
            String name = (String) s.get("name");
            long count = ((Number) s.getOrDefault("count", 0)).longValue();
            long prev = i == 0 ? count : ((Number) stages.get(i - 1).getOrDefault("count", 0)).longValue();
            double stepRate = prev == 0 ? 0 : (double) count / prev;
            double cumRate = (double) count / top;
            double dropPct = i == 0 ? 0 : (1 - stepRate) * 100;
            if (i > 0 && dropPct > biggestDropPct) {
                biggestDropPct = dropPct;
                leakStage = name;
            }

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("stage", name);
            r.put("count", count);
            r.put("stepConversionRate", round(stepRate * 100, 2));
            r.put("cumulativeConversionRate", round(cumRate * 100, 2));
            r.put("dropFromPrevPct", round(dropPct, 2));
            r.put("suggestedExperiment", suggest(name));
            rows.add(r);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skill", "funnelanalyzer");
        result.put("topOfFunnel", top);
        result.put("bottomOfFunnel", ((Number) stages.get(stages.size() - 1).getOrDefault("count", 0)).longValue());
        result.put("overallConversion", round(((Number) stages.get(stages.size() - 1).getOrDefault("count", 0)).doubleValue() / top * 100, 2));
        result.put("biggestLeakStage", leakStage);
        result.put("biggestLeakDropPct", round(biggestDropPct, 2));
        result.put("stages", rows);
        return result;
    }

    private static String suggest(String stage) {
        if (stage == null) return null;
        String key = stage.toLowerCase(Locale.ROOT);
        for (var e : EXPERIMENTS.entrySet()) if (key.contains(e.getKey())) return e.getValue();
        return "Run user-session recordings on this stage to find the friction point";
    }

    private static double round(double v, int p) {
        double f = Math.pow(10, p);
        return Math.round(v * f) / f;
    }
}
