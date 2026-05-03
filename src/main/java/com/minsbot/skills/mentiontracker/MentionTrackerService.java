package com.minsbot.skills.mentiontracker;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Polls user-supplied search RSS feeds (Google Alerts, Reddit search, HN
 * Algolia, Nitter X-search) for product mentions. Deduplicates by content
 * hash. Classifies sentiment (basic lexicon). Writes new mentions to disk
 * so they survive restarts and don't repeat.
 */
@Service
public class MentionTrackerService {

    private final MentionTrackerConfig.MentionTrackerProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;
    private Path dir;

    private static final Pattern RSS_ITEM = Pattern.compile(
            "<item\\b[^>]*>(.*?)</item>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ATOM_ENTRY = Pattern.compile(
            "<entry\\b[^>]*>(.*?)</entry>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile(
            "<title\\b[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern LINK_RSS = Pattern.compile(
            "<link\\b[^>]*>(.*?)</link>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern LINK_ATOM = Pattern.compile(
            "<link\\b[^>]*href=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern DESC = Pattern.compile(
            "<(?:description|summary|content)\\b[^>]*>(.*?)</(?:description|summary|content)>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HTML_TAGS = Pattern.compile("<[^>]+>");

    private static final Set<String> POS = Set.of("love", "great", "amazing", "awesome", "recommend",
            "fantastic", "excellent", "best", "fast", "easy", "works");
    private static final Set<String> NEG = Set.of("hate", "terrible", "awful", "broken", "buggy",
            "slow", "useless", "garbage", "worse", "scam", "expensive");

    public MentionTrackerService(MentionTrackerConfig.MentionTrackerProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    @PostConstruct
    void init() throws IOException {
        dir = Paths.get(props.getStorageDir());
        Files.createDirectories(dir);
    }

    public Map<String, Object> poll(List<String> brandKeywords, List<String> sources) throws IOException {
        if (brandKeywords == null) brandKeywords = List.of();
        if (sources == null) sources = List.of();
        int cap = Math.min(sources.size(), props.getMaxSources());
        List<String> kwLower = brandKeywords.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();

        Set<String> seen = loadSeenIds();
        List<Map<String, Object>> newMentions = new ArrayList<>();
        long pos = 0, neg = 0, neu = 0;
        List<Map<String, Object>> sourceReport = new ArrayList<>();

        for (int i = 0; i < cap; i++) {
            String src = sources.get(i);
            Map<String, Object> sr = new LinkedHashMap<>();
            sr.put("url", src);
            try {
                String body = fetch(src);
                List<Map<String, Object>> items = parseFeed(body);
                int matched = 0, fresh = 0;
                for (Map<String, Object> item : items) {
                    String title = (String) item.getOrDefault("title", "");
                    String snippet = (String) item.getOrDefault("snippet", "");
                    String hay = (title + " " + snippet).toLowerCase(Locale.ROOT);
                    boolean kwOk = kwLower.isEmpty() || kwLower.stream().anyMatch(hay::contains);
                    if (!kwOk) continue;
                    matched++;
                    String id = hash((String) item.getOrDefault("url", title) + "|" + title);
                    if (seen.contains(id)) continue;
                    fresh++;
                    int p = 0, n = 0;
                    for (String w : hay.split("[^a-z]+")) {
                        if (POS.contains(w)) p++;
                        if (NEG.contains(w)) n++;
                    }
                    String sentiment = p > n ? "positive" : n > p ? "negative" : "neutral";
                    if (sentiment.equals("positive")) pos++;
                    else if (sentiment.equals("negative")) neg++;
                    else neu++;

                    Map<String, Object> rec = new LinkedHashMap<>(item);
                    rec.put("id", id);
                    rec.put("source", src);
                    rec.put("sentiment", sentiment);
                    rec.put("foundAt", Instant.now().toString());
                    Files.writeString(dir.resolve(id + ".json"), mapper.writeValueAsString(rec));
                    seen.add(id);
                    newMentions.add(rec);
                }
                sr.put("itemsParsed", items.size());
                sr.put("matched", matched);
                sr.put("newMentions", fresh);
                sr.put("ok", true);
            } catch (Exception e) {
                sr.put("ok", false);
                sr.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            sourceReport.add(sr);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skill", "mentiontracker");
        result.put("polledAt", Instant.now().toString());
        result.put("brandKeywords", brandKeywords);
        result.put("sourcesQueried", cap);
        result.put("newMentionCount", newMentions.size());
        result.put("positive", pos);
        result.put("negative", neg);
        result.put("neutral", neu);
        result.put("sources", sourceReport);
        result.put("newMentions", newMentions);
        return result;
    }

    public List<Map<String, Object>> recent(int limit) throws IOException {
        List<Map<String, Object>> all = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (Path p : stream.toList()) {
                if (!p.toString().endsWith(".json")) continue;
                try { all.add(mapper.readValue(Files.readString(p), Map.class)); }
                catch (Exception ignored) {}
            }
        }
        all.sort((a, b) -> {
            Object x = b.getOrDefault("foundAt", "");
            Object y = a.getOrDefault("foundAt", "");
            return x.toString().compareTo(y.toString());
        });
        return all.size() > limit ? all.subList(0, limit) : all;
    }

    private Set<String> loadSeenIds() throws IOException {
        Set<String> ids = new HashSet<>();
        try (var stream = Files.list(dir)) {
            stream.forEach(p -> {
                String name = p.getFileName().toString();
                if (name.endsWith(".json")) ids.add(name.substring(0, name.length() - 5));
            });
        }
        return ids;
    }

    private String fetch(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .timeout(Duration.ofMillis(props.getTimeoutMs()))
                .header("User-Agent", props.getUserAgent())
                .header("Accept", "application/rss+xml, application/atom+xml, */*").GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) throw new RuntimeException("HTTP " + resp.statusCode());
        String body = resp.body();
        if (body.length() > props.getMaxFetchBytes()) body = body.substring(0, props.getMaxFetchBytes());
        return body;
    }

    private static List<Map<String, Object>> parseFeed(String body) {
        List<Map<String, Object>> items = new ArrayList<>();
        Matcher m = RSS_ITEM.matcher(body);
        boolean atom = false;
        if (!m.find()) {
            m = ATOM_ENTRY.matcher(body);
            atom = true;
        } else { m.reset(); }
        while (m.find()) {
            String chunk = m.group(1);
            Map<String, Object> item = new LinkedHashMap<>();
            Matcher t = TITLE.matcher(chunk);
            if (t.find()) item.put("title", clean(t.group(1)));
            Matcher l = atom ? LINK_ATOM.matcher(chunk) : LINK_RSS.matcher(chunk);
            if (l.find()) item.put("url", clean(l.group(1)));
            Matcher d = DESC.matcher(chunk);
            if (d.find()) item.put("snippet", stripHtml(clean(d.group(1))));
            if (item.containsKey("title")) items.add(item);
        }
        return items;
    }

    private static String hash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] b = md.digest(s.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.substring(0, 16);
        } catch (Exception e) { return Integer.toHexString(s.hashCode()); }
    }

    private static String stripHtml(String s) {
        return HTML_TAGS.matcher(s).replaceAll(" ").replaceAll("\\s+", " ").trim();
    }
    private static String clean(String s) {
        return s.replace("<![CDATA[", "").replace("]]>", "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").trim();
    }
}
