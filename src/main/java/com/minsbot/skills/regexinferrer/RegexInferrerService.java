package com.minsbot.skills.regexinferrer;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

@Service
public class RegexInferrerService {

    public Map<String, Object> infer(List<String> examples) {
        if (examples == null || examples.isEmpty()) throw new IllegalArgumentException("examples required");

        // Strategy: compute char class at each position across all examples, then collapse runs.
        int minLen = examples.stream().mapToInt(String::length).min().orElse(0);
        int maxLen = examples.stream().mapToInt(String::length).max().orElse(0);
        boolean sameLen = minLen == maxLen;

        List<String> positionClasses = new ArrayList<>();
        for (int i = 0; i < minLen; i++) {
            Set<Character> chars = new HashSet<>();
            for (String s : examples) chars.add(s.charAt(i));
            positionClasses.add(classifyChars(chars));
        }

        // Collapse consecutive identical classes into {N} or + quantifiers
        StringBuilder regex = new StringBuilder("^");
        int i = 0;
        while (i < positionClasses.size()) {
            String cls = positionClasses.get(i);
            int j = i;
            while (j < positionClasses.size() && positionClasses.get(j).equals(cls)) j++;
            int count = j - i;
            regex.append(cls);
            if (count > 1) regex.append("{").append(count).append("}");
            i = j;
        }
        if (!sameLen) regex.append(".*");
        regex.append("$");

        String pattern = regex.toString();
        boolean validates = true;
        List<String> failed = new ArrayList<>();
        try {
            Pattern p = Pattern.compile(pattern);
            for (String ex : examples) {
                if (!p.matcher(ex).matches()) { validates = false; failed.add(ex); }
            }
        } catch (Exception e) {
            validates = false;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("examples", examples);
        out.put("pattern", pattern);
        out.put("sameLength", sameLen);
        out.put("minLength", minLen);
        out.put("maxLength", maxLen);
        out.put("matchesAllExamples", validates);
        if (!validates) out.put("failedExamples", failed);
        out.put("alternativeLoose", looseAlternation(examples));
        return out;
    }

    private static String classifyChars(Set<Character> chars) {
        boolean allDigit = true, allLower = true, allUpper = true, allAlpha = true, allAlnum = true;
        boolean allSpace = true, literal = chars.size() == 1;
        for (char c : chars) {
            if (!Character.isDigit(c)) allDigit = false;
            if (!Character.isLowerCase(c)) allLower = false;
            if (!Character.isUpperCase(c)) allUpper = false;
            if (!Character.isLetter(c)) allAlpha = false;
            if (!Character.isLetterOrDigit(c)) allAlnum = false;
            if (!Character.isWhitespace(c)) allSpace = false;
        }
        if (literal) {
            char c = chars.iterator().next();
            if ("\\.^$|?*+()[]{}".indexOf(c) >= 0) return "\\" + c;
            return String.valueOf(c);
        }
        if (allDigit) return "\\d";
        if (allLower) return "[a-z]";
        if (allUpper) return "[A-Z]";
        if (allAlpha) return "[A-Za-z]";
        if (allAlnum) return "\\w";
        if (allSpace) return "\\s";
        return ".";
    }

    private static String looseAlternation(List<String> examples) {
        StringBuilder sb = new StringBuilder("^(?:");
        for (int i = 0; i < examples.size(); i++) {
            if (i > 0) sb.append("|");
            sb.append(Pattern.quote(examples.get(i)));
        }
        sb.append(")$");
        return sb.toString();
    }
}
