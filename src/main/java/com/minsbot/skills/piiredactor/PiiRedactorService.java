package com.minsbot.skills.piiredactor;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PiiRedactorService {

    private record Rule(String id, String tag, Pattern pattern) {}

    private static final List<Rule> RULES = List.of(
            new Rule("email",       "[EMAIL]",      Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")),
            new Rule("phone-us",    "[PHONE]",      Pattern.compile("(?:\\+?1[\\s.-]?)?\\(?\\d{3}\\)?[\\s.-]?\\d{3}[\\s.-]?\\d{4}\\b")),
            new Rule("phone-intl",  "[PHONE]",      Pattern.compile("\\+\\d{1,3}[\\s.-]?\\d{2,4}[\\s.-]?\\d{3,4}[\\s.-]?\\d{3,4}")),
            new Rule("ssn",         "[SSN]",        Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b")),
            new Rule("credit-card", "[CREDIT_CARD]",Pattern.compile("\\b(?:\\d[ -]*?){13,19}\\b")),
            new Rule("ipv4",        "[IP]",         Pattern.compile("\\b(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d{1,2})\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d{1,2})\\b")),
            new Rule("iban",        "[IBAN]",       Pattern.compile("\\b[A-Z]{2}\\d{2}[A-Z0-9]{10,30}\\b")),
            new Rule("passport-us", "[PASSPORT]",   Pattern.compile("\\b[A-Z]{1,2}\\d{6,9}\\b")),
            new Rule("date-dob",    "[DATE]",       Pattern.compile("\\b(?:0?[1-9]|1[0-2])[/-](?:0?[1-9]|[12]\\d|3[01])[/-](?:19|20)\\d{2}\\b")),
            new Rule("url",         "[URL]",        Pattern.compile("https?://[^\\s]+"))
    );

    public Map<String, Object> redact(String text, List<String> onlyTypes) {
        if (text == null) text = "";
        Set<String> enabled = onlyTypes == null ? null : new HashSet<>(onlyTypes);
        String out = text;
        Map<String, Integer> hits = new LinkedHashMap<>();
        List<Map<String, Object>> findings = new ArrayList<>();

        for (Rule r : RULES) {
            if (enabled != null && !enabled.contains(r.id())) continue;
            Matcher m = r.pattern().matcher(out);
            StringBuilder sb = new StringBuilder();
            int lastEnd = 0;
            int count = 0;
            while (m.find()) {
                sb.append(out, lastEnd, m.start()).append(r.tag());
                findings.add(Map.of("type", r.id(), "start", m.start(), "end", m.end(), "match", m.group()));
                count++;
                lastEnd = m.end();
            }
            sb.append(out, lastEnd, out.length());
            out = sb.toString();
            if (count > 0) hits.put(r.id(), count);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("redacted", out);
        result.put("counts", hits);
        result.put("totalFound", findings.size());
        result.put("findings", findings);
        return result;
    }

    public List<String> supportedTypes() {
        return RULES.stream().map(Rule::id).toList();
    }
}
