package com.minsbot.skills.headlineanalyzer;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class HeadlineAnalyzerService {

    private static final Set<String> POWER = Set.of(
            "you","your","free","new","now","proven","secret","guaranteed","incredible","amazing","ultimate",
            "essential","unbelievable","best","top","powerful","breakthrough","instant","easy","simple"
    );
    private static final Set<String> EMOTIONAL = Set.of(
            "love","hate","fear","desperate","horrifying","shocking","heartwarming","stunning","surprising",
            "jealous","amazed","delighted","terrible","awesome","miserable","unforgettable","brilliant","proud"
    );
    private static final Set<String> COMMON_POS = Set.of(
            "a","an","the","and","but","or","for","in","on","at","to","of","with","by","from","as",
            "is","are","was","were","be","been","that","this","it","its","how","why","what","when"
    );
    private static final Set<String> NUMBERS_WORDS = Set.of(
            "one","two","three","four","five","six","seven","eight","nine","ten","fifty","hundred","thousand"
    );

    public Map<String, Object> analyze(String headline) {
        if (headline == null) headline = "";
        String trimmed = headline.trim();
        String lower = trimmed.toLowerCase();
        String[] words = trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");

        int chars = trimmed.length();
        int wordCount = words.length;
        boolean hasNumber = trimmed.matches(".*\\d.*");
        boolean hasNumberWord = false;
        int powerHits = 0, emotionalHits = 0, commonHits = 0, uncommon = 0;
        List<String> powers = new ArrayList<>();
        List<String> emotions = new ArrayList<>();

        for (String w : words) {
            String clean = w.toLowerCase().replaceAll("[^a-z0-9']", "");
            if (clean.isEmpty()) continue;
            if (POWER.contains(clean)) { powerHits++; if (!powers.contains(clean)) powers.add(clean); }
            if (EMOTIONAL.contains(clean)) { emotionalHits++; if (!emotions.contains(clean)) emotions.add(clean); }
            if (NUMBERS_WORDS.contains(clean)) hasNumberWord = true;
            if (COMMON_POS.contains(clean)) commonHits++; else uncommon++;
        }

        int score = 50;
        List<String> notes = new ArrayList<>();
        if (wordCount >= 6 && wordCount <= 12) { score += 10; notes.add("Word count in sweet spot (6-12)"); }
        else if (wordCount < 5) { score -= 10; notes.add("Too short (<5 words)"); }
        else if (wordCount > 15) { score -= 10; notes.add("Too long (>15 words)"); }

        if (chars >= 40 && chars <= 70) { score += 5; notes.add("Character length optimal (40-70)"); }
        if (hasNumber || hasNumberWord) { score += 10; notes.add("Contains a number — makes it concrete"); }
        if (powerHits > 0) { score += Math.min(15, powerHits * 5); notes.add(powerHits + " power word(s): " + powers); }
        if (emotionalHits > 0) { score += Math.min(10, emotionalHits * 3); notes.add(emotionalHits + " emotional word(s): " + emotions); }
        if (lower.startsWith("how ") || lower.startsWith("why ") || lower.startsWith("what ")) { score += 5; notes.add("Question-style opener"); }
        if (uncommon < 3) { score -= 5; notes.add("Mostly common words — low information density"); }
        score = Math.max(0, Math.min(100, score));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("headline", trimmed);
        out.put("charCount", chars);
        out.put("wordCount", wordCount);
        out.put("hasNumber", hasNumber || hasNumberWord);
        out.put("powerWords", powers);
        out.put("emotionalWords", emotions);
        out.put("commonWordCount", commonHits);
        out.put("uncommonWordCount", uncommon);
        out.put("score", score);
        out.put("rating", score >= 80 ? "excellent" : score >= 65 ? "good" : score >= 50 ? "okay" : "weak");
        out.put("notes", notes);
        return out;
    }
}
