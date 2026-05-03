package com.minsbot.skills.socialschedule;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Generates platform-tailored social posts from a single brief and validates
 * each against platform character limits. Suggests optimal posting times
 * (rough heuristics — user can override) and hashtags from supplied keywords.
 */
@Service
public class SocialScheduleService {

    private static final Map<String, Integer> LIMITS = Map.of(
            "x", 280,
            "linkedin", 3000,
            "instagram", 2200,
            "threads", 500,
            "facebook", 63206,
            "bluesky", 300
    );

    // Rough best-time-of-day heuristics (local time, hour of day)
    private static final Map<String, List<String>> BEST_TIMES = Map.of(
            "x", List.of("09:00", "12:00", "17:00"),
            "linkedin", List.of("08:00", "12:00", "17:30"),
            "instagram", List.of("11:00", "14:00", "20:00"),
            "threads", List.of("12:00", "19:00"),
            "facebook", List.of("13:00", "15:00"),
            "bluesky", List.of("10:00", "16:00")
    );

    public Map<String, Object> generate(String brief, String cta, List<String> keywords,
                                        List<String> platforms) {
        if (brief == null) brief = "";
        if (cta == null) cta = "";
        if (keywords == null) keywords = List.of();
        if (platforms == null || platforms.isEmpty()) platforms = new ArrayList<>(LIMITS.keySet());

        List<String> hashtags = new ArrayList<>();
        for (String k : keywords) {
            String tag = "#" + k.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (tag.length() > 1 && !hashtags.contains(tag)) hashtags.add(tag);
        }

        List<Map<String, Object>> posts = new ArrayList<>();
        for (String p : platforms) {
            String key = p.toLowerCase(Locale.ROOT);
            int limit = LIMITS.getOrDefault(key, 1000);
            String text = composeFor(key, brief, cta, hashtags);
            if (text.length() > limit) text = text.substring(0, limit - 3) + "...";

            Map<String, Object> post = new LinkedHashMap<>();
            post.put("platform", key);
            post.put("text", text);
            post.put("length", text.length());
            post.put("limit", limit);
            post.put("ok", text.length() <= limit);
            post.put("suggestedTimes", BEST_TIMES.getOrDefault(key, List.of("12:00")));
            posts.add(post);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skill", "socialschedule");
        result.put("brief", brief);
        result.put("hashtags", hashtags);
        result.put("posts", posts);
        return result;
    }

    private static String composeFor(String platform, String brief, String cta, List<String> hashtags) {
        String tags = hashtags.isEmpty() ? "" : " " + String.join(" ", hashtags);
        return switch (platform) {
            case "x", "bluesky" -> truncate(brief, 200) + (cta.isBlank() ? "" : "\n\n→ " + cta) + tags;
            case "linkedin" -> brief + "\n\n" + (cta.isBlank() ? "" : cta + "\n\n") + tags;
            case "instagram" -> brief + "\n.\n.\n.\n" + tags + (cta.isBlank() ? "" : "\n\n" + cta);
            case "threads" -> truncate(brief, 380) + (cta.isBlank() ? "" : "\n\n" + cta) + tags;
            case "facebook" -> brief + "\n\n" + (cta.isBlank() ? "" : cta);
            default -> brief + tags;
        };
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
