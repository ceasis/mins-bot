package com.minsbot.skills.personabuilder;

import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds an ICP (Ideal Customer Profile) by fetching user-supplied source URLs
 * (subreddit RSS, forum threads, review pages) and extracting: pain signals,
 * vocabulary, decision-maker job titles, common objections.
 */
@Service
public class PersonaBuilderService {

    private final PersonaBuilderConfig.PersonaBuilderProperties props;
    private final HttpClient http;

    private static final Pattern HTML_TAGS = Pattern.compile("<[^>]+>");
    private static final Pattern PAIN = Pattern.compile(
            "\\b(struggle\\s+with|frustrat\\w+|hate\\s+\\w+|wish\\s+\\w+|can'?t\\s+\\w+|annoying|broken|slow|expensive|complicated|confusing|tired\\s+of|fed\\s+up)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OBJECTION = Pattern.compile(
            "\\b(too\\s+expensive|too\\s+complicated|don'?t\\s+trust|not\\s+sure|skeptical|worried\\s+about|concern\\s+about|hesitant|already\\s+use|stick\\s+with)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE_HINT = Pattern.compile(
            "\\b(CEO|CTO|CFO|COO|founder|co-founder|director|VP|head\\s+of\\s+[a-z]+|manager|lead|engineer|developer|designer|marketer|pm|product\\s+manager|owner|consultant)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WORD = Pattern.compile("[a-zA-Z]{5,}");
    private static final Set<String> STOP = Set.of("about", "above", "after", "again", "against", "their", "there",
            "these", "those", "where", "which", "while", "would", "could", "should", "before", "between");

    public PersonaBuilderService(PersonaBuilderConfig.PersonaBuilderProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    public Map<String, Object> build(String businessType, List<String> sources) {
        if (sources == null) sources = List.of();
        int cap = Math.min(sources.size(), props.getMaxSources());

        List<String> pains = new ArrayList<>();
        List<String> objections = new ArrayList<>();
        Set<String> titles = new LinkedHashSet<>();
        Map<String, Integer> vocab = new HashMap<>();
        List<Map<String, Object>> sourceReport = new ArrayList<>();

        for (int i = 0; i < cap; i++) {
            String src = sources.get(i);
            Map<String, Object> sr = new LinkedHashMap<>();
            sr.put("url", src);
            try {
                String body = stripHtml(fetch(src));
                addAll(PAIN, body, pains, 80);
                addAll(OBJECTION, body, objections, 80);
                Matcher t = TITLE_HINT.matcher(body);
                while (t.find()) titles.add(t.group(1).toLowerCase(Locale.ROOT));
                Matcher w = WORD.matcher(body.toLowerCase(Locale.ROOT));
                while (w.find()) {
                    String word = w.group();
                    if (STOP.contains(word)) continue;
                    vocab.merge(word, 1, Integer::sum);
                }
                sr.put("ok", true);
            } catch (Exception e) {
                sr.put("ok", false);
                sr.put("error", e.getMessage());
            }
            sourceReport.add(sr);
        }

        List<Map<String, Object>> topVocab = new ArrayList<>();
        vocab.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(25)
                .forEach(e -> topVocab.add(Map.of("word", e.getKey(), "count", e.getValue())));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skill", "personabuilder");
        result.put("businessType", businessType == null ? "" : businessType);
        result.put("sourcesScanned", cap);
        result.put("pains", dedupe(pains));
        result.put("objections", dedupe(objections));
        result.put("decisionMakerTitles", new ArrayList<>(titles));
        result.put("vocabulary", topVocab);
        result.put("sources", sourceReport);
        return result;
    }

    private static List<String> dedupe(List<String> xs) {
        return new ArrayList<>(new LinkedHashSet<>(xs));
    }

    private static void addAll(Pattern p, String text, List<String> bucket, int cap) {
        Matcher m = p.matcher(text);
        while (m.find() && bucket.size() < cap) {
            int s = Math.max(0, m.start() - 30);
            int e = Math.min(text.length(), m.end() + 60);
            bucket.add(text.substring(s, e).replaceAll("\\s+", " ").trim());
        }
    }

    private String fetch(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .timeout(Duration.ofMillis(props.getTimeoutMs()))
                .header("User-Agent", props.getUserAgent()).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) throw new RuntimeException("HTTP " + resp.statusCode());
        String body = resp.body();
        if (body.length() > props.getMaxFetchBytes()) body = body.substring(0, props.getMaxFetchBytes());
        return body;
    }

    private static String stripHtml(String s) {
        return HTML_TAGS.matcher(s).replaceAll(" ").replaceAll("\\s+", " ").trim();
    }
}
