package com.minsbot.skills.flashcardmaker;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class FlashcardMakerService {

    public Map<String, Object> fromQaPairs(List<Map<String, String>> pairs) {
        if (pairs == null || pairs.isEmpty()) throw new IllegalArgumentException("pairs required");
        StringBuilder csv = new StringBuilder();
        csv.append("Front,Back\n");
        List<Map<String, Object>> cards = new ArrayList<>();
        for (Map<String, String> p : pairs) {
            String q = p.getOrDefault("question", p.get("front"));
            String a = p.getOrDefault("answer", p.get("back"));
            if (q == null || a == null) continue;
            csv.append("\"").append(q.replace("\"", "\"\"")).append("\",");
            csv.append("\"").append(a.replace("\"", "\"\"")).append("\"\n");
            cards.add(Map.of("front", q, "back", a));
        }
        return Map.of("count", cards.size(), "cards", cards, "ankiCsv", csv.toString());
    }

    public Map<String, Object> fromDelimitedText(String text, String separator) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("text required");
        String sep = separator == null || separator.isEmpty() ? "::" : separator;
        List<Map<String, String>> pairs = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int idx = line.indexOf(sep);
            if (idx < 0) continue;
            String q = line.substring(0, idx).trim();
            String a = line.substring(idx + sep.length()).trim();
            pairs.add(Map.of("question", q, "answer", a));
        }
        return fromQaPairs(pairs);
    }

    public Map<String, Object> fromMarkdownHeaders(String markdown) {
        if (markdown == null) markdown = "";
        List<Map<String, String>> pairs = new ArrayList<>();
        String[] blocks = markdown.split("(?m)^#+\\s+", -1);
        String[] headers = extractHeaders(markdown);
        for (int i = 0; i < headers.length && i + 1 < blocks.length; i++) {
            String body = blocks[i + 1].replaceAll("(?m)^#+\\s+.*$", "").trim();
            if (!body.isEmpty()) pairs.add(Map.of("question", headers[i].trim(), "answer", body));
        }
        return fromQaPairs(pairs);
    }

    private static String[] extractHeaders(String markdown) {
        List<String> out = new ArrayList<>();
        for (String line : markdown.split("\\r?\\n")) {
            if (line.matches("^#+\\s+.+$")) out.add(line.replaceAll("^#+\\s+", ""));
        }
        return out.toArray(new String[0]);
    }
}
