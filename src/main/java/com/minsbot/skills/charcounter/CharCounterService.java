package com.minsbot.skills.charcounter;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class CharCounterService {

    // Platform limits: [charLimit, recommendedLimit (or 0 if none), notes]
    private static final Map<String, int[]> LIMITS = new LinkedHashMap<>();
    static {
        LIMITS.put("x-twitter",          new int[]{280, 0});
        LIMITS.put("x-twitter-premium",  new int[]{25000, 0});
        LIMITS.put("linkedin-post",      new int[]{3000, 1300});
        LIMITS.put("linkedin-headline",  new int[]{220, 0});
        LIMITS.put("linkedin-summary",   new int[]{2600, 0});
        LIMITS.put("facebook-post",      new int[]{63206, 80});
        LIMITS.put("instagram-caption",  new int[]{2200, 125});
        LIMITS.put("instagram-bio",      new int[]{150, 0});
        LIMITS.put("tiktok-caption",     new int[]{2200, 150});
        LIMITS.put("youtube-title",      new int[]{100, 70});
        LIMITS.put("youtube-description",new int[]{5000, 157});
        LIMITS.put("pinterest-pin",      new int[]{500, 215});
        LIMITS.put("threads-post",       new int[]{500, 0});
        LIMITS.put("bluesky-post",       new int[]{300, 0});
        LIMITS.put("mastodon-post",      new int[]{500, 0});
        LIMITS.put("reddit-title",       new int[]{300, 0});
        LIMITS.put("reddit-post",        new int[]{40000, 0});
        LIMITS.put("sms",                new int[]{160, 0});
        LIMITS.put("email-subject",      new int[]{78, 60});
        LIMITS.put("seo-title",          new int[]{60, 55});
        LIMITS.put("seo-description",    new int[]{160, 155});
    }

    public Map<String, Object> count(String text, List<String> platformKeys) {
        if (text == null) text = "";
        int chars = text.length();
        int charsNoSpaces = text.replaceAll("\\s", "").length();
        int words = text.trim().isEmpty() ? 0 : text.trim().split("\\s+").length;
        int lines = text.isEmpty() ? 0 : text.split("\\r?\\n").length;

        List<Map<String, Object>> platforms = new ArrayList<>();
        Collection<String> keys = (platformKeys == null || platformKeys.isEmpty()) ? LIMITS.keySet() : platformKeys;
        for (String key : keys) {
            int[] lim = LIMITS.get(key);
            if (lim == null) continue;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("platform", key);
            p.put("limit", lim[0]);
            if (lim[1] > 0) p.put("recommended", lim[1]);
            p.put("remaining", lim[0] - chars);
            p.put("overBy", Math.max(0, chars - lim[0]));
            p.put("fits", chars <= lim[0]);
            platforms.add(p);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chars", chars);
        result.put("charsNoSpaces", charsNoSpaces);
        result.put("words", words);
        result.put("lines", lines);
        result.put("platforms", platforms);
        return result;
    }

    public Set<String> supportedPlatforms() {
        return LIMITS.keySet();
    }
}
