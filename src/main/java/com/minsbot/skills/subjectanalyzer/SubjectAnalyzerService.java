package com.minsbot.skills.subjectanalyzer;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class SubjectAnalyzerService {

    private static final Set<String> SPAM_WORDS = Set.of(
            "free","buy now","click here","limited time","act now","cash","winner","guarantee","risk-free",
            "no obligation","100% free","order now","urgent","congratulations","earn money","make money",
            "double your income","lowest price","best price","cheap","discount","promo","bonus","prize",
            "$$$","!!!","???","call now","subscribe","unsubscribe","viagra","casino","loan","credit"
    );

    private static final Set<String> URGENCY_WORDS = Set.of(
            "now","today","urgent","hurry","limited","deadline","last chance","expires","soon","quick","fast","instant"
    );

    private static final Set<String> POWER_WORDS = Set.of(
            "new","free","save","exclusive","secret","proven","introducing","revealed","discover","you","your"
    );

    public Map<String, Object> analyze(String subject) {
        if (subject == null) subject = "";
        String trimmed = subject.trim();
        String lower = trimmed.toLowerCase();

        int length = trimmed.length();
        int wordCount = trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length;

        int letters = 0, uppers = 0;
        for (char c : trimmed.toCharArray()) {
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) uppers++;
            }
        }
        double upperRatio = letters == 0 ? 0 : (double) uppers / letters;

        int exclamations = countChar(trimmed, '!');
        int questions = countChar(trimmed, '?');
        int dollarSigns = countChar(trimmed, '$');
        boolean hasEmoji = trimmed.codePoints().anyMatch(cp -> cp >= 0x1F300 && cp <= 0x1FAFF);

        List<String> spamHits = new ArrayList<>();
        for (String w : SPAM_WORDS) if (lower.contains(w)) spamHits.add(w);
        List<String> urgencyHits = new ArrayList<>();
        for (String w : URGENCY_WORDS) if (lower.matches(".*\\b" + w + "\\b.*")) urgencyHits.add(w);
        List<String> powerHits = new ArrayList<>();
        for (String w : POWER_WORDS) if (lower.matches(".*\\b" + w + "\\b.*")) powerHits.add(w);

        int score = 100;
        List<String> notes = new ArrayList<>();
        if (length == 0) { score = 0; notes.add("Subject is empty"); }
        if (length > 0 && length < 20) { score -= 10; notes.add("Short subject (<20 chars)"); }
        if (length > 60) { score -= 10; notes.add("Long subject (>60 chars, may be truncated on mobile)"); }
        if (length > 78) { score -= 10; notes.add("Very long (>78 chars)"); }
        if (upperRatio > 0.5 && letters > 5) { score -= 15; notes.add("More than 50% uppercase — looks shouty"); }
        if (exclamations >= 2) { score -= 10; notes.add(exclamations + " exclamation marks"); }
        if (questions >= 2) { score -= 5; notes.add(questions + " question marks"); }
        if (dollarSigns >= 2) { score -= 10; notes.add(dollarSigns + " dollar signs"); }
        if (!spamHits.isEmpty()) { score -= 5 * spamHits.size(); notes.add("Spam-trigger words: " + spamHits); }
        if (powerHits.isEmpty()) notes.add("Consider adding power words (discover, new, you, save, etc.)");
        score = Math.max(0, Math.min(100, score));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("subject", trimmed);
        result.put("length", length);
        result.put("wordCount", wordCount);
        result.put("upperRatio", Math.round(upperRatio * 100.0) / 100.0);
        result.put("exclamations", exclamations);
        result.put("questions", questions);
        result.put("dollarSigns", dollarSigns);
        result.put("hasEmoji", hasEmoji);
        result.put("spamTriggers", spamHits);
        result.put("urgencyWords", urgencyHits);
        result.put("powerWords", powerHits);
        result.put("score", score);
        result.put("notes", notes);
        return result;
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }
}
