package com.minsbot.skills.keywordcluster;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Groups seed keywords by intent (informational/commercial/navigational/transactional)
 * and shared head terms. Suggests an article brief per cluster.
 */
@Service
public class KeywordClusterService {

    private static final Set<String> INFORMATIONAL = Set.of(
            "how", "what", "why", "when", "where", "guide", "tutorial", "examples", "tips", "ideas", "vs");
    private static final Set<String> COMMERCIAL = Set.of(
            "best", "top", "review", "reviews", "comparison", "alternatives", "cheap", "affordable");
    private static final Set<String> TRANSACTIONAL = Set.of(
            "buy", "price", "discount", "deal", "coupon", "free", "trial", "demo", "pricing", "subscribe");
    private static final Set<String> NAVIGATIONAL = Set.of("login", "sign in", "download", "app", "official");
    private static final Set<String> STOP = Set.of("a", "an", "the", "for", "of", "in", "on", "to", "and", "or");

    public Map<String, Object> cluster(List<String> keywords) {
        if (keywords == null) keywords = List.of();

        Map<String, List<String>> byIntent = new LinkedHashMap<>();
        byIntent.put("informational", new ArrayList<>());
        byIntent.put("commercial", new ArrayList<>());
        byIntent.put("transactional", new ArrayList<>());
        byIntent.put("navigational", new ArrayList<>());
        byIntent.put("other", new ArrayList<>());

        Map<String, List<String>> byHead = new LinkedHashMap<>();

        for (String raw : keywords) {
            String kw = raw.toLowerCase(Locale.ROOT).trim();
            if (kw.isEmpty()) continue;
            String intent = classifyIntent(kw);
            byIntent.get(intent).add(kw);
            String head = headTerm(kw);
            byHead.computeIfAbsent(head, k -> new ArrayList<>()).add(kw);
        }

        List<Map<String, Object>> clusters = new ArrayList<>();
        for (var e : byHead.entrySet()) {
            if (e.getValue().size() < 2) continue;
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("head", e.getKey());
            c.put("keywords", e.getValue());
            c.put("count", e.getValue().size());
            c.put("suggestedBrief", "Pillar article on '" + e.getKey() + "' covering: "
                    + String.join(", ", e.getValue().subList(0, Math.min(5, e.getValue().size()))));
            clusters.add(c);
        }
        clusters.sort((a, b) -> Integer.compare((int) b.get("count"), (int) a.get("count")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skill", "keywordcluster");
        result.put("totalKeywords", keywords.size());
        result.put("byIntent", byIntent);
        result.put("clusters", clusters);
        return result;
    }

    private static String classifyIntent(String kw) {
        for (String w : kw.split("\\s+")) {
            if (TRANSACTIONAL.contains(w)) return "transactional";
            if (NAVIGATIONAL.contains(w)) return "navigational";
            if (COMMERCIAL.contains(w)) return "commercial";
            if (INFORMATIONAL.contains(w)) return "informational";
        }
        return "other";
    }

    private static String headTerm(String kw) {
        // longest non-stop bigram or last 2 words
        String[] parts = kw.split("\\s+");
        List<String> meaningful = new ArrayList<>();
        for (String p : parts) if (!STOP.contains(p) && !INFORMATIONAL.contains(p)
                && !COMMERCIAL.contains(p) && !TRANSACTIONAL.contains(p)) meaningful.add(p);
        if (meaningful.isEmpty()) return parts[parts.length - 1];
        if (meaningful.size() == 1) return meaningful.get(0);
        return meaningful.get(meaningful.size() - 2) + " " + meaningful.get(meaningful.size() - 1);
    }
}
