package com.minsbot.skills.adcopygen;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Generates ad copy variants (headlines, descriptions, CTAs) from a product
 * brief. Validates each against platform length limits (Google Ads, Meta Ads).
 * Pure templating — no external calls.
 */
@Service
public class AdCopyGenService {

    private final AdCopyGenConfig.AdCopyGenProperties props;

    private static final String[] HEADLINE_TEMPLATES = {
            "%s — %s",
            "Get %s Today",
            "The Best %s for %s",
            "Try %s Free",
            "%s Made Simple",
            "Stop Wasting Time on %s",
            "%s in Minutes, Not Hours",
            "Why %s Choose %s",
            "%s: %s",
            "Save Time with %s",
            "%s — Built for %s",
            "Finally, A %s That Works"
    };

    private static final String[] CTA_TEMPLATES = {
            "Start Free Trial",
            "Get Started",
            "Claim Yours Now",
            "Try It Free",
            "See Pricing",
            "Book a Demo",
            "Sign Up Today",
            "Learn More",
            "Get Instant Access",
            "Buy Now"
    };

    private static final String[] DESC_TEMPLATES = {
            "%s helps %s achieve %s. Trusted by thousands. %s.",
            "Stop struggling with %s. %s gives you %s in minutes. %s.",
            "Built for %s who care about %s. Get %s today. %s.",
            "Discover how %s solves %s for %s. %s.",
            "%s is the fastest way to %s. No credit card needed. %s."
    };

    // Platform limits (chars)
    static final int GOOGLE_HEADLINE = 30;
    static final int GOOGLE_DESC = 90;
    static final int META_HEADLINE = 40;
    static final int META_DESC = 125;
    static final int X_TWEET = 280;

    public AdCopyGenService(AdCopyGenConfig.AdCopyGenProperties props) {
        this.props = props;
    }

    public Map<String, Object> generate(String product, String audience, String benefit,
                                        List<String> keywords, Integer maxVariants) {
        if (product == null) product = "Product";
        if (audience == null) audience = "you";
        if (benefit == null) benefit = "results";
        if (keywords == null) keywords = List.of();
        int cap = maxVariants == null ? props.getMaxVariants() : Math.min(maxVariants, props.getMaxVariants());

        List<Map<String, Object>> headlines = new ArrayList<>();
        for (String tpl : HEADLINE_TEMPLATES) {
            long count = tpl.chars().filter(c -> c == '%').count();
            String h = count == 1 ? String.format(tpl, product)
                    : count == 2 ? String.format(tpl, product, benefit)
                    : tpl;
            headlines.add(scoreCopy(h, GOOGLE_HEADLINE, META_HEADLINE));
            if (!keywords.isEmpty()) {
                String kw = keywords.get(0);
                String hk = count == 1 ? String.format(tpl, kw)
                        : count == 2 ? String.format(tpl, kw, benefit) : tpl;
                headlines.add(scoreCopy(hk, GOOGLE_HEADLINE, META_HEADLINE));
            }
        }

        List<Map<String, Object>> descriptions = new ArrayList<>();
        for (String tpl : DESC_TEMPLATES) {
            String cta = CTA_TEMPLATES[descriptions.size() % CTA_TEMPLATES.length];
            long count = tpl.chars().filter(c -> c == '%').count();
            String d = count == 4 ? String.format(tpl, product, audience, benefit, cta)
                    : count == 3 ? String.format(tpl, product, audience, cta)
                    : tpl;
            descriptions.add(scoreCopy(d, GOOGLE_DESC, META_DESC));
        }

        if (headlines.size() > cap) headlines = headlines.subList(0, cap);
        if (descriptions.size() > cap) descriptions = descriptions.subList(0, cap);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skill", "adcopygen");
        result.put("product", product);
        result.put("audience", audience);
        result.put("benefit", benefit);
        result.put("headlines", headlines);
        result.put("descriptions", descriptions);
        result.put("ctas", Arrays.asList(CTA_TEMPLATES));
        result.put("limits", Map.of(
                "googleHeadline", GOOGLE_HEADLINE, "googleDesc", GOOGLE_DESC,
                "metaHeadline", META_HEADLINE, "metaDesc", META_DESC,
                "xTweet", X_TWEET));
        return result;
    }

    private static Map<String, Object> scoreCopy(String text, int limitGoogle, int limitMeta) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", text);
        m.put("length", text.length());
        m.put("googleOk", text.length() <= limitGoogle);
        m.put("metaOk", text.length() <= limitMeta);
        return m;
    }
}
