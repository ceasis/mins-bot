package com.minsbot.skills.writingtools;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class WritingToolsService {

    private static final Pattern PASSIVE = Pattern.compile(
            "\\b(am|is|are|was|were|be|been|being|get|got|gets|getting)\\s+\\w+(ed|en)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ADVERB = Pattern.compile("\\b\\w+ly\\b", Pattern.CASE_INSENSITIVE);
    private static final Set<String> WEASEL = Set.of(
            "very","really","quite","just","actually","basically","literally","simply","perhaps","maybe",
            "somewhat","somehow","kind of","sort of","rather","seems","appears"
    );

    public Map<String, Object> analyze(String text, int wpm) {
        if (text == null) text = "";
        String trimmed = text.trim();
        int chars = trimmed.length();
        int charsNoSpaces = trimmed.replaceAll("\\s", "").length();
        String[] words = trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
        int wordCount = words.length;
        int sentences = Math.max(1, trimmed.split("[.!?]+").length);
        int paragraphs = trimmed.isEmpty() ? 0 : trimmed.split("\\n\\s*\\n").length;

        List<Map<String, Object>> passiveMatches = findAll(PASSIVE, trimmed, 50);
        List<Map<String, Object>> adverbMatches = findAll(ADVERB, trimmed, 200);

        int weaselCount = 0;
        List<String> weaselFound = new ArrayList<>();
        String lower = trimmed.toLowerCase();
        for (String w : WEASEL) {
            Pattern p = Pattern.compile("\\b" + Pattern.quote(w) + "\\b");
            Matcher m = p.matcher(lower);
            while (m.find()) { weaselCount++; if (!weaselFound.contains(w)) weaselFound.add(w); }
        }

        double avgWordsPerSentence = (double) wordCount / sentences;
        int readingTimeMin = (int) Math.ceil(wordCount / (double) Math.max(1, wpm));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chars", chars);
        out.put("charsNoSpaces", charsNoSpaces);
        out.put("words", wordCount);
        out.put("sentences", sentences);
        out.put("paragraphs", paragraphs);
        out.put("avgWordsPerSentence", Math.round(avgWordsPerSentence * 100.0) / 100.0);
        out.put("readingTimeMinutes", readingTimeMin);
        out.put("passiveVoiceCount", passiveMatches.size());
        out.put("passiveSamples", passiveMatches.size() > 10 ? passiveMatches.subList(0, 10) : passiveMatches);
        out.put("adverbCount", adverbMatches.size());
        out.put("adverbRatio", wordCount == 0 ? 0 : Math.round((adverbMatches.size() * 100.0 / wordCount) * 100.0) / 100.0);
        out.put("weaselWordCount", weaselCount);
        out.put("weaselWordsFound", weaselFound);
        return out;
    }

    private static List<Map<String, Object>> findAll(Pattern p, String text, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        Matcher m = p.matcher(text);
        while (m.find() && out.size() < limit) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("match", m.group());
            entry.put("offset", m.start());
            out.add(entry);
        }
        return out;
    }
}
