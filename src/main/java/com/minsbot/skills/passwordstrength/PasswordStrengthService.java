package com.minsbot.skills.passwordstrength;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class PasswordStrengthService {

    private static final Set<String> COMMON_TOP = Set.of(
            "123456","password","12345678","qwerty","123456789","12345","1234","111111","1234567","dragon",
            "123123","baseball","abc123","football","monkey","letmein","696969","shadow","master","666666",
            "qwertyuiop","123321","mustang","1234567890","michael","654321","superman","1qaz2wsx","7777777",
            "121212","000000","qazwsx","123qwe","killer","trustno1","jordan","jennifer","zxcvbnm","asdfgh",
            "hunter","buster","soccer","harley","batman","andrew","tigger","sunshine","iloveyou","2000"
    );

    private static final String[][] KEYBOARD_ROWS = {
            {"qwertyuiop","asdfghjkl","zxcvbnm"},
            {"1234567890"}
    };

    public Map<String, Object> evaluate(String password) {
        if (password == null) password = "";
        int len = password.length();

        int lower = 0, upper = 0, digit = 0, symbol = 0, unique;
        Set<Character> seen = new HashSet<>();
        for (char c : password.toCharArray()) {
            if (c >= 'a' && c <= 'z') lower++;
            else if (c >= 'A' && c <= 'Z') upper++;
            else if (c >= '0' && c <= '9') digit++;
            else if (c > ' ') symbol++;
            seen.add(c);
        }
        unique = seen.size();

        int poolSize = (lower > 0 ? 26 : 0) + (upper > 0 ? 26 : 0) + (digit > 0 ? 10 : 0) + (symbol > 0 ? 32 : 0);
        double entropy = len == 0 || poolSize == 0 ? 0 : len * (Math.log(poolSize) / Math.log(2));

        List<String> issues = new ArrayList<>();
        List<String> strengths = new ArrayList<>();

        if (len == 0) { issues.add("Password is empty"); }
        if (len > 0 && len < 8) issues.add("Too short (<8 chars)");
        else if (len >= 16) strengths.add("Length >= 16");
        else if (len >= 12) strengths.add("Length >= 12");

        if (poolSize < 26) issues.add("Single character class — add mixed case/digits/symbols");
        else if (poolSize >= 94) strengths.add("Uses all 4 character classes");

        if (unique < Math.max(3, len / 2)) issues.add("Low character diversity");
        if (hasSequential(password)) issues.add("Contains sequential characters (e.g. abc, 123, qwerty)");
        if (hasRepeats(password, 3)) issues.add("Contains 3+ repeated characters");
        if (COMMON_TOP.contains(password.toLowerCase())) issues.add("Matches a top-50 common password");

        int score = Math.min(100, (int) Math.round(entropy * 1.5));
        if (!issues.isEmpty()) score -= issues.size() * 8;
        score = Math.max(0, Math.min(100, score));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("length", len);
        result.put("lowercase", lower);
        result.put("uppercase", upper);
        result.put("digits", digit);
        result.put("symbols", symbol);
        result.put("uniqueChars", unique);
        result.put("charsetPoolSize", poolSize);
        result.put("entropyBits", Math.round(entropy * 100.0) / 100.0);
        result.put("score", score);
        result.put("strength", scoreLabel(score));
        result.put("issues", issues);
        result.put("strengths", strengths);
        result.put("crackTimeEstimate", estimateCrack(entropy));
        return result;
    }

    private static boolean hasSequential(String s) {
        String lower = s.toLowerCase();
        for (String[] rows : KEYBOARD_ROWS) {
            for (String row : rows) {
                for (int i = 0; i + 3 <= row.length(); i++) {
                    String chunk = row.substring(i, i + 3);
                    if (lower.contains(chunk) || lower.contains(new StringBuilder(chunk).reverse().toString())) return true;
                }
            }
        }
        for (int i = 0; i + 2 < s.length(); i++) {
            char a = s.charAt(i), b = s.charAt(i + 1), c = s.charAt(i + 2);
            if (b - a == 1 && c - b == 1) return true;
            if (a - b == 1 && b - c == 1) return true;
        }
        return false;
    }

    private static boolean hasRepeats(String s, int n) {
        int run = 1;
        for (int i = 1; i < s.length(); i++) {
            if (s.charAt(i) == s.charAt(i - 1)) { run++; if (run >= n) return true; }
            else run = 1;
        }
        return false;
    }

    private static String scoreLabel(int score) {
        if (score < 25) return "very-weak";
        if (score < 50) return "weak";
        if (score < 70) return "fair";
        if (score < 85) return "strong";
        return "very-strong";
    }

    private static String estimateCrack(double entropyBits) {
        // Assume 10^10 guesses/sec offline attack.
        double guessesPerSec = 1e10;
        double totalGuesses = Math.pow(2, entropyBits);
        double seconds = totalGuesses / guessesPerSec;
        if (seconds < 1) return "< 1 second (offline brute force)";
        if (seconds < 60) return Math.round(seconds) + " seconds";
        if (seconds < 3600) return Math.round(seconds / 60) + " minutes";
        if (seconds < 86400) return Math.round(seconds / 3600) + " hours";
        if (seconds < 31536000L) return Math.round(seconds / 86400) + " days";
        if (seconds < 31536000L * 100) return Math.round(seconds / 31536000L) + " years";
        if (seconds < 31536000L * 1_000_000L) return Math.round(seconds / 31536000L) + " years";
        return "centuries+";
    }
}
