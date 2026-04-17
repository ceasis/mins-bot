package com.minsbot.skills.sluggenerator;

import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class SlugGeneratorService {

    private static final Set<String> STOPWORDS = Set.of(
            "a","an","the","and","or","but","of","to","in","on","at","for","with","by","from","as","is","are","was","were"
    );

    public String slugify(String input, String separator, boolean lowercase, boolean stripStop, int maxLength) {
        if (input == null) return "";
        String sep = (separator == null || separator.isEmpty()) ? "-" : separator;

        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        if (lowercase) normalized = normalized.toLowerCase();

        normalized = normalized.replaceAll("[^a-zA-Z0-9\\s-_]", " ").replaceAll("[_\\s-]+", " ").trim();

        String[] tokens = normalized.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String t : tokens) {
            if (t.isEmpty()) continue;
            if (stripStop && STOPWORDS.contains(t.toLowerCase())) continue;
            if (sb.length() > 0) sb.append(sep);
            sb.append(t);
            if (sb.length() >= maxLength) break;
        }

        String result = sb.toString();
        if (result.length() > maxLength) {
            result = result.substring(0, maxLength).replaceAll(sep + "[^" + sep + "]*$", "");
        }
        return result;
    }

    public List<String> variations(String input, int maxLength) {
        Set<String> out = new LinkedHashSet<>();
        out.add(slugify(input, "-", true, false, maxLength));
        out.add(slugify(input, "-", true, true, maxLength));
        out.add(slugify(input, "_", true, false, maxLength));
        out.add(slugify(input, "", true, false, maxLength));
        return out.stream().filter(s -> !s.isEmpty()).toList();
    }
}
