package com.minsbot.skills.keywordextractor;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Service
public class KeywordExtractorService {

    private final KeywordExtractorConfig.KeywordExtractorProperties properties;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final Set<String> STOPWORDS = Set.of(
            "a","an","the","and","or","but","if","then","else","of","to","in","on","at","for","with","by","from","as",
            "is","are","was","were","be","been","being","have","has","had","do","does","did","will","would","should",
            "can","could","may","might","must","shall","this","that","these","those","it","its","i","you","he","she",
            "we","they","them","his","her","our","their","my","your","me","us","him","so","no","not","yes","about",
            "up","down","out","over","under","again","any","all","some","more","most","other","into","through","than",
            "also","too","very","just","only","own","same","such","here","there","when","where","why","how","what",
            "which","who","whom","whose","while","because","between","during","before","after","above","below"
    );

    public KeywordExtractorService(KeywordExtractorConfig.KeywordExtractorProperties properties) {
        this.properties = properties;
    }

    public Map<String, Object> extractFromText(String text, int topN, int ngramMax, boolean keepStopwords) {
        String cleaned = text == null ? "" : text.toLowerCase();
        if (cleaned.length() > properties.getMaxTextChars()) {
            cleaned = cleaned.substring(0, properties.getMaxTextChars());
        }
        List<String> tokens = tokenize(cleaned);
        List<String> filtered = keepStopwords ? tokens : tokens.stream().filter(t -> !STOPWORDS.contains(t)).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalTokens", tokens.size());
        result.put("uniqueTokens", new HashSet<>(tokens).size());

        Map<String, Object> byN = new LinkedHashMap<>();
        for (int n = 1; n <= Math.max(1, ngramMax); n++) {
            byN.put(n + "-gram", topCounts(ngrams(filtered, n), topN));
        }
        result.put("ngrams", byN);
        return result;
    }

    public Map<String, Object> extractFromUrl(String url, int topN, int ngramMax, boolean keepStopwords) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                .header("User-Agent", "MinsBot-KeywordExtractor/1.0")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) throw new IOException("HTTP " + resp.statusCode());
        String body = resp.body();
        String text = body.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("&[a-z]+;", " ");
        Map<String, Object> out = extractFromText(text, topN, ngramMax, keepStopwords);
        out.put("url", url);
        return out;
    }

    private static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '\'') {
                sb.append(c);
            } else {
                if (sb.length() > 1) tokens.add(sb.toString());
                sb.setLength(0);
            }
        }
        if (sb.length() > 1) tokens.add(sb.toString());
        return tokens;
    }

    private static List<String> ngrams(List<String> tokens, int n) {
        if (n <= 1) return tokens;
        List<String> out = new ArrayList<>();
        for (int i = 0; i <= tokens.size() - n; i++) {
            out.add(String.join(" ", tokens.subList(i, i + n)));
        }
        return out;
    }

    private static List<Map<String, Object>> topCounts(List<String> items, int topN) {
        Map<String, Long> counts = new HashMap<>();
        for (String item : items) counts.merge(item, 1L, Long::sum);
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(topN)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("term", e.getKey());
                    m.put("count", e.getValue());
                    return m;
                })
                .toList();
    }
}
