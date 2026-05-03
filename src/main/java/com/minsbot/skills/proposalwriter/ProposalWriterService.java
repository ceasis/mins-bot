package com.minsbot.skills.proposalwriter;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Generates 3 cold-pitch proposal variants from a lead snippet + your services.
 * Pure templating — no LLM, no external calls.
 */
@Service
public class ProposalWriterService {

    public Map<String, Object> write(String leadSnippet, String yourName, String yourService,
                                     String priceAnchor, List<String> proofPoints) {
        if (leadSnippet == null) leadSnippet = "";
        if (yourName == null) yourName = "I";
        if (yourService == null) yourService = "this";
        if (priceAnchor == null) priceAnchor = "starting at $X";
        if (proofPoints == null) proofPoints = List.of();

        String hook = extractHook(leadSnippet);
        String proof = proofPoints.isEmpty() ? "I've done this before for similar clients."
                : "Recent results: " + String.join(" • ", proofPoints) + ".";

        List<Map<String, Object>> variants = new ArrayList<>();

        variants.add(variant("Direct",
                "Saw your post about " + hook + ".",
                "I do " + yourService + ". " + proof,
                "Pricing: " + priceAnchor + ".",
                "Want me to send a quick scope + timeline? — " + yourName));

        variants.add(variant("Question-led",
                "Quick question about " + hook + " — is this still open?",
                "Reason I ask: I specialize in " + yourService + ". " + proof,
                priceAnchor.startsWith("$") ? "Budget-friendly: " + priceAnchor + "." : priceAnchor + ".",
                "Happy to share 2-3 examples if useful. — " + yourName));

        variants.add(variant("Result-anchored",
                "Re: " + hook + " — here's what I'd do.",
                "Step 1: scope it tight. Step 2: deliver in [timeframe]. " + proof,
                "Investment: " + priceAnchor + ", fixed.",
                "If that fits, reply with 'send scope' and I'll have it back within 24h. — " + yourName));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skill", "proposalwriter");
        result.put("leadHook", hook);
        result.put("variants", variants);
        result.put("tip", "Send within 4 hours of post for best response rate.");
        return result;
    }

    private static Map<String, Object> variant(String style, String hook, String value,
                                               String price, String cta) {
        String full = hook + "\n\n" + value + "\n\n" + price + "\n\n" + cta;
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("style", style);
        v.put("hook", hook);
        v.put("valueProp", value);
        v.put("priceLine", price);
        v.put("cta", cta);
        v.put("fullText", full);
        v.put("length", full.length());
        return v;
    }

    private static String extractHook(String snippet) {
        String s = snippet.trim();
        if (s.isEmpty()) return "your project";
        if (s.length() > 80) s = s.substring(0, 77) + "...";
        return s;
    }
}
