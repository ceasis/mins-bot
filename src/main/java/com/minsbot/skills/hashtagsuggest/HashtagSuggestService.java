package com.minsbot.skills.hashtagsuggest;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class HashtagSuggestService {

    private static final Set<String> STOPWORDS = Set.of(
            "a","an","the","and","or","but","if","of","to","in","on","at","for","with","by","from","as",
            "is","are","was","were","be","been","have","has","had","do","does","did","will","would","should",
            "this","that","these","those","it","its","i","you","he","she","we","they","them","his","her",
            "our","their","my","your","me","us","him","so","no","not","yes","about","up","down","out","over",
            "under","any","all","some","more","most","other","into","through","than","also","too","very",
            "just","only","same","such","here","there","when","where","why","how","what","which","who"
    );

    public Map<String, Object> suggest(String text, int topN, boolean includeExisting) {
        if (text == null) text = "";
        String lower = text.toLowerCase();

        // Pull existing hashtags already present in the text
        List<String> existing = new ArrayList<>();
        int idx = 0;
        while ((idx = lower.indexOf('#', idx)) != -1) {
            int end = idx + 1;
            while (end < lower.length() && (Character.isLetterOrDigit(lower.charAt(end)) || lower.charAt(end) == '_')) end++;
            if (end > idx + 1) existing.add(lower.substring(idx, end));
            idx = end;
        }

        List<String> tokens = tokenize(lower).stream()
                .filter(t -> !STOPWORDS.contains(t) && t.length() >= 3)
                .toList();

        Map<String, Long> unigrams = new HashMap<>();
        for (String t : tokens) unigrams.merge(t, 1L, Long::sum);

        Map<String, Long> bigrams = new HashMap<>();
        for (int i = 0; i + 1 < tokens.size(); i++) {
            bigrams.merge(tokens.get(i) + tokens.get(i + 1), 1L, Long::sum);
        }

        List<Map.Entry<String, Long>> candidates = new ArrayList<>();
        unigrams.forEach((k, v) -> { if (v >= 2) candidates.add(Map.entry(k, v)); });
        bigrams.forEach((k, v) -> { if (v >= 2) candidates.add(Map.entry(k, v * 2)); });

        if (candidates.isEmpty()) {
            // fallback: take most frequent unigrams even with count 1
            unigrams.forEach((k, v) -> candidates.add(Map.entry(k, v)));
        }

        candidates.sort(Map.Entry.<String, Long>comparingByValue().reversed());

        List<String> hashtags = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map.Entry<String, Long> c : candidates) {
            String tag = "#" + c.getKey();
            if (seen.add(tag)) hashtags.add(tag);
            if (hashtags.size() >= topN) break;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("existingHashtags", existing);
        if (includeExisting) {
            List<String> merged = new ArrayList<>(existing);
            for (String h : hashtags) if (!merged.contains(h)) merged.add(h);
            if (merged.size() > topN) merged = merged.subList(0, topN);
            result.put("suggestions", merged);
        } else {
            result.put("suggestions", hashtags);
        }
        return result;
    }

    private static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(c);
            else {
                if (sb.length() > 0) { tokens.add(sb.toString()); sb.setLength(0); }
            }
        }
        if (sb.length() > 0) tokens.add(sb.toString());
        return tokens;
    }
}
