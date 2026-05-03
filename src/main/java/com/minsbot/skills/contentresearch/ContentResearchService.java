package com.minsbot.skills.contentresearch;

import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Researches trending content / SEO opportunities from user-supplied feeds
 * (Reddit RSS, Hacker News, Substack feeds, niche blogs, Google News RSS).
 * Scores by topic match, "viral title" patterns, and recency.
 *
 * Output: ranked list of headlines + suggested article angles.
 */
@Service
public class ContentResearchService {

    private final ContentResearchConfig.ContentResearchProperties props;
    private final HttpClient http;

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
    private static final Pattern PUB = Pattern.compile(
            "<(?:pubDate|published|updated)\\b[^>]*>(.*?)</(?:pubDate|published|updated)>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_TAGS = Pattern.compile("<[^>]+>");
    private static final Pattern VIRAL_PATTERNS = Pattern.compile(
            "\\b(how\\s+to|why|the\\s+\\d+|top\\s+\\d+|best\\s+\\d+|\\d+\\s+(?:ways|things|reasons|tips|tricks)|guide|ultimate|vs|review|comparison|secret|surprising|nobody\\s+talks)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER_TITLE = Pattern.compile("^\\s*\\d+\\b");

    public ContentResearchService(ContentResearchConfig.ContentResearchProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    public Map<String, Object> run(List<String> topics, List<String> sources,
                                   Integer maxAgeDays, Integer maxResults) {
        if (topics == null) topics = List.of();
        if (sources == null) sources = List.of();
        int cap = maxResults == null ? props.getMaxResults() : Math.min(maxResults, props.getMaxResults());
        int ageDays = maxAgeDays == null ? 30 : maxAgeDays;
        List<String> topicLower = topics.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();

        List<Map<String, Object>> findings = new ArrayList<>();
        List<Map<String, Object>> sourceReport = new ArrayList<>();
        Map<String, Integer> topicFrequency = new HashMap<>();
        int srcCount = Math.min(sources.size(), props.getMaxSources());

        for (int i = 0; i < srcCount; i++) {
            String src = sources.get(i);
            Map<String, Object> srcInfo = new LinkedHashMap<>();
            srcInfo.put("url", src);
            try {
                String body = fetch(src);
                List<Map<String, Object>> items = parseFeed(body);
                int matched = 0;
                for (Map<String, Object> item : items) {
                    String title = item.getOrDefault("title", "").toString();
                    String hay = (title + " " + item.getOrDefault("snippet", ""))
                            .toLowerCase(Locale.ROOT);
                    int topicScore = 0;
                    List<String> matchedTopics = new ArrayList<>();
                    for (String t : topicLower) {
                        if (t.isBlank()) continue;
                        if (hay.contains(t)) { topicScore++; matchedTopics.add(t); }
                    }
                    if (topicScore == 0 && !topicLower.isEmpty()) continue;

                    int viralScore = 0;
                    List<String> viralHits = new ArrayList<>();
                    Matcher v = VIRAL_PATTERNS.matcher(title);
                    while (v.find()) { viralScore++; viralHits.add(v.group(1)); }
                    if (NUMBER_TITLE.matcher(title).find()) viralScore++;

                    Long ageHours = ageHoursFromPub(item.get("published"));
                    if (ageHours != null && ageHours > ageDays * 24L) continue;
                    int recencyScore = ageHours == null ? 0 :
                            ageHours < 24 ? 5 : ageHours < 72 ? 3 : ageHours < 168 ? 2 : 1;

                    int score = topicScore * 3 + viralScore * 2 + recencyScore;
                    item.put("score", score);
                    item.put("topicMatches", matchedTopics);
                    item.put("viralPatterns", viralHits);
                    item.put("ageHours", ageHours);
                    item.put("source", src);
                    item.put("suggestedAngle", suggestAngle(title, matchedTopics));
                    findings.add(item);
                    matchedTopics.forEach(t -> topicFrequency.merge(t, 1, Integer::sum));
                    matched++;
                }
                srcInfo.put("itemsFound", items.size());
                srcInfo.put("matched", matched);
                srcInfo.put("ok", true);
            } catch (Exception e) {
                srcInfo.put("ok", false);
                srcInfo.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            sourceReport.add(srcInfo);
        }

        findings.sort((a, b) -> Integer.compare((int) b.get("score"), (int) a.get("score")));
        if (findings.size() > cap) findings = findings.subList(0, cap);

        List<Map<String, Object>> trending = new ArrayList<>();
        topicFrequency.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(10)
                .forEach(e -> trending.add(Map.of("topic", e.getKey(), "mentions", e.getValue())));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skill", "contentresearch");
        result.put("topics", topics);
        result.put("sourcesQueried", srcCount);
        result.put("totalMatches", findings.size());
        result.put("trendingTopics", trending);
        result.put("sources", sourceReport);
        result.put("findings", findings);
        return result;
    }

    private static String suggestAngle(String title, List<String> topics) {
        if (topics.isEmpty()) return null;
        String t = topics.get(0);
        return "Counter-take or deep-dive on '" + t + "' inspired by: " + truncate(title, 80);
    }

    private static Long ageHoursFromPub(Object pubObj) {
        if (pubObj == null) return null;
        String s = pubObj.toString().trim();
        if (s.isEmpty()) return null;
        try {
            ZonedDateTime z = ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME);
            return Duration.between(z.toInstant(), java.time.Instant.now()).toHours();
        } catch (Exception ignored) {}
        try {
            OffsetDateTime o = OffsetDateTime.parse(s);
            return Duration.between(o.toInstant(), java.time.Instant.now()).toHours();
        } catch (Exception ignored) {}
        return null;
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
            if (d.find()) item.put("snippet", truncate(stripHtml(clean(d.group(1))), 400));
            Matcher p = PUB.matcher(chunk);
            if (p.find()) item.put("published", clean(p.group(1)));
            if (item.containsKey("title")) items.add(item);
        }
        return items;
    }

    private static String stripHtml(String s) {
        if (s == null) return "";
        return decode(HTML_TAGS.matcher(s).replaceAll(" ")).replaceAll("\\s+", " ").trim();
    }
    private static String clean(String s) {
        if (s == null) return "";
        s = s.replace("<![CDATA[", "").replace("]]>", "").trim();
        return decode(s);
    }
    private static String decode(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'").replace("&nbsp;", " ");
    }
    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
