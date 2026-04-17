package com.minsbot.skills.regextester;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Service
public class RegexTesterService {

    public Map<String, Object> test(String pattern, String flags, String input, int maxMatches) {
        int flagInt = parseFlags(flags);
        Pattern compiled;
        try {
            compiled = Pattern.compile(pattern, flagInt);
        } catch (PatternSyntaxException e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("valid", false);
            err.put("error", e.getDescription());
            err.put("index", e.getIndex());
            return err;
        }

        List<Map<String, Object>> matches = new ArrayList<>();
        Matcher m = compiled.matcher(input);
        int count = 0;
        boolean truncated = false;
        while (m.find()) {
            if (count >= maxMatches) { truncated = true; break; }
            Map<String, Object> match = new LinkedHashMap<>();
            match.put("match", m.group());
            match.put("start", m.start());
            match.put("end", m.end());
            if (m.groupCount() > 0) {
                List<String> groups = new ArrayList<>();
                for (int i = 1; i <= m.groupCount(); i++) {
                    groups.add(m.group(i));
                }
                match.put("groups", groups);
            }
            matches.add(match);
            count++;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", true);
        result.put("pattern", pattern);
        result.put("flags", flags == null ? "" : flags);
        result.put("matchCount", matches.size());
        result.put("truncated", truncated);
        result.put("matches", matches);
        return result;
    }

    public Map<String, Object> replace(String pattern, String flags, String input, String replacement, boolean all) {
        int flagInt = parseFlags(flags);
        Pattern compiled;
        try {
            compiled = Pattern.compile(pattern, flagInt);
        } catch (PatternSyntaxException e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("valid", false);
            err.put("error", e.getDescription());
            return err;
        }
        String result = all
                ? compiled.matcher(input).replaceAll(replacement)
                : compiled.matcher(input).replaceFirst(replacement);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("valid", true);
        resp.put("result", result);
        return resp;
    }

    private static int parseFlags(String flags) {
        if (flags == null || flags.isEmpty()) return 0;
        int f = 0;
        for (char c : flags.toCharArray()) {
            switch (c) {
                case 'i' -> f |= Pattern.CASE_INSENSITIVE;
                case 'm' -> f |= Pattern.MULTILINE;
                case 's' -> f |= Pattern.DOTALL;
                case 'x' -> f |= Pattern.COMMENTS;
                case 'u' -> f |= Pattern.UNICODE_CASE;
                default -> throw new IllegalArgumentException("Unknown flag: " + c);
            }
        }
        return f;
    }
}
