package com.minsbot.skills.readability;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ReadabilityService {

    public Map<String, Object> analyze(String text, int maxChars) {
        if (text == null) text = "";
        if (text.length() > maxChars) text = text.substring(0, maxChars);

        int charCount = text.length();
        int charCountNoSpaces = text.replaceAll("\\s", "").length();

        String[] words = text.trim().isEmpty() ? new String[0] : text.trim().split("\\s+");
        int wordCount = words.length;

        int sentenceCount = Math.max(1, text.split("[.!?]+").length);

        int syllableCount = 0;
        int complexWords = 0;
        for (String w : words) {
            int s = countSyllables(w);
            syllableCount += s;
            if (s >= 3) complexWords++;
        }

        double avgWordsPerSentence = wordCount == 0 ? 0 : (double) wordCount / sentenceCount;
        double avgSyllablesPerWord = wordCount == 0 ? 0 : (double) syllableCount / wordCount;

        // Flesch Reading Ease: 206.835 - 1.015*(words/sentences) - 84.6*(syllables/words)
        double flesch = 206.835 - 1.015 * avgWordsPerSentence - 84.6 * avgSyllablesPerWord;

        // Flesch-Kincaid Grade: 0.39*(words/sentences) + 11.8*(syllables/words) - 15.59
        double fkGrade = 0.39 * avgWordsPerSentence + 11.8 * avgSyllablesPerWord - 15.59;

        // Gunning Fog: 0.4 * (words/sentences + 100*complex/words)
        double fog = wordCount == 0 ? 0 : 0.4 * (avgWordsPerSentence + 100.0 * complexWords / wordCount);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("charCount", charCount);
        result.put("charCountNoSpaces", charCountNoSpaces);
        result.put("wordCount", wordCount);
        result.put("sentenceCount", sentenceCount);
        result.put("syllableCount", syllableCount);
        result.put("complexWordCount", complexWords);
        result.put("avgWordsPerSentence", round(avgWordsPerSentence));
        result.put("avgSyllablesPerWord", round(avgSyllablesPerWord));
        result.put("fleschReadingEase", round(flesch));
        result.put("fleschReadingEaseLabel", fleschLabel(flesch));
        result.put("fleschKincaidGrade", round(fkGrade));
        result.put("gunningFog", round(fog));
        return result;
    }

    private static int countSyllables(String raw) {
        String word = raw.toLowerCase().replaceAll("[^a-z]", "");
        if (word.isEmpty()) return 0;
        if (word.length() <= 3) return 1;
        word = word.replaceAll("(?:[^laeiouy]es|ed|[^laeiouy]e)$", "");
        word = word.replaceAll("^y", "");
        int count = 0;
        boolean prevVowel = false;
        for (char c : word.toCharArray()) {
            boolean vowel = "aeiouy".indexOf(c) >= 0;
            if (vowel && !prevVowel) count++;
            prevVowel = vowel;
        }
        return Math.max(1, count);
    }

    private static String fleschLabel(double score) {
        if (score >= 90) return "Very Easy (5th grade)";
        if (score >= 80) return "Easy (6th grade)";
        if (score >= 70) return "Fairly Easy (7th grade)";
        if (score >= 60) return "Standard (8-9th grade)";
        if (score >= 50) return "Fairly Difficult (10-12th grade)";
        if (score >= 30) return "Difficult (college)";
        return "Very Difficult (college graduate)";
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
