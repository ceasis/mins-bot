package com.minsbot.skills.reviewmonitor;

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
 * Scans review feeds (G2/Trustpilot/App Store RSS exports), classifies each
 * review as positive/negative, extracts top complaint themes, and generates
 * a reply template per negative review.
 */
@Service
public class ReviewMonitorService {

    private final ReviewMonitorConfig.ReviewMonitorProperties props;
    private final HttpClient http;

    private static final Pattern RSS_ITEM = Pattern.compile(
            "<item\\b[^>]*>(.*?)</item>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ATOM_ENTRY = Pattern.compile(
            "<entry\\b[^>]*>(.*?)</entry>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile(
            "<title\\b[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern DESC = Pattern.compile(
            "<(?:description|summary|content)\\b[^>]*>(.*?)</(?:description|summary|content)>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern STAR = Pattern.compile("(\\d)\\s*(?:star|/\\s*5)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_TAGS = Pattern.compile("<[^>]+>");

    private static final Set<String> POS = Set.of("love", "loved", "great", "excellent", "amazing", "fast",
            "easy", "intuitive", "perfect", "awesome", "fantastic", "saved", "recommend", "best");
    private static final Set<String> NEG = Set.of("hate", "terrible", "awful", "slow", "broken", "buggy",
            "crash", "crashes", "unusable", "garbage", "useless", "frustrating", "confusing", "expensive",
            "overpriced", "missing", "doesnt", "support", "refund", "cancel");
    private static final List<String> COMPLAINT_THEMES = List.of("speed", "price", "support", "bug",
            "missing feature", "ux", "onboarding", "billing", "documentation", "reliability");

    public ReviewMonitorService(ReviewMonitorConfig.ReviewMonitorProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    public Map<String, Object> scan(List<String> sources) {
        if (sources == null) sources = List.of();
        int cap = Math.min(sources.size(), props.getMaxSources());

        List<Map<String, Object>> reviews = new ArrayList<>();
        Map<String, Integer> themeCount = new HashMap<>();

        for (int i = 0; i < cap; i++) {
            String src = sources.get(i);
            try {
                String body = fetch(src);
                List<Map<String, Object>> items = parseFeed(body);
                for (Map<String, Object> item : items) {
                    String text = (item.getOrDefault("title", "") + " "
                            + item.getOrDefault("snippet", "")).toString();
                    String low = text.toLowerCase(Locale.ROOT);
                    int pos = 0, neg = 0;
                    for (String w : low.split("[^a-z]+")) {
                        if (POS.contains(w)) pos++;
                        if (NEG.contains(w)) neg++;
                    }
                    Integer star = extractStar(text);
                    String sentiment = star != null ? (star >= 4 ? "positive" : star <= 2 ? "negative" : "neutral")
                            : (pos > neg ? "positive" : neg > pos ? "negative" : "neutral");

                    List<String> themes = new ArrayList<>();
                    for (String th : COMPLAINT_THEMES) if (low.contains(th)) themes.add(th);
                    if ("negative".equals(sentiment)) {
                        for (String th : themes) themeCount.merge(th, 1, Integer::sum);
                    }

                    item.put("sentiment", sentiment);
                    item.put("stars", star);
                    item.put("themes", themes);
                    item.put("source", src);
                    if ("negative".equals(sentiment)) {
                        item.put("replyTemplate", buildReply(themes, text));
                    }
                    reviews.add(item);
                }
            } catch (Exception ignored) {}
        }

        long positive = reviews.stream().filter(r -> "positive".equals(r.get("sentiment"))).count();
        long negative = reviews.stream().filter(r -> "negative".equals(r.get("sentiment"))).count();
        long neutral = reviews.size() - positive - negative;

        List<Map<String, Object>> topThemes = new ArrayList<>();
        themeCount.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(10)
                .forEach(e -> topThemes.add(Map.of("theme", e.getKey(), "negativeMentions", e.getValue())));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skill", "reviewmonitor");
        result.put("sourcesScanned", cap);
        result.put("totalReviews", reviews.size());
        result.put("positive", positive);
        result.put("negative", negative);
        result.put("neutral", neutral);
        result.put("topComplaintThemes", topThemes);
        result.put("reviews", reviews);
        return result;
    }

    private static Integer extractStar(String text) {
        Matcher m = STAR.matcher(text);
        if (m.find()) try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
        return null;
    }

    private static String buildReply(List<String> themes, String text) {
        String themePart = themes.isEmpty() ? "your feedback"
                : "your concerns about " + String.join(" and ", themes);
        return "Hi — really sorry to hear about this. We take " + themePart
                + " seriously. Could you reply with a contact email? I'd like to look into your case personally and make it right.";
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

    private static List<Map<String, Object>> parseFeed(String body) {
        List<Map<String, Object>> items = new ArrayList<>();
        Matcher m = RSS_ITEM.matcher(body);
        if (!m.find()) m = ATOM_ENTRY.matcher(body);
        else m.reset();
        while (m.find()) {
            String chunk = m.group(1);
            Map<String, Object> item = new LinkedHashMap<>();
            Matcher t = TITLE.matcher(chunk);
            if (t.find()) item.put("title", clean(t.group(1)));
            Matcher d = DESC.matcher(chunk);
            if (d.find()) item.put("snippet", stripHtml(clean(d.group(1))));
            if (item.containsKey("title")) items.add(item);
        }
        return items;
    }

    private static String stripHtml(String s) { return HTML_TAGS.matcher(s).replaceAll(" ").replaceAll("\\s+", " ").trim(); }
    private static String clean(String s) {
        return s.replace("<![CDATA[", "").replace("]]>", "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").trim();
    }
}
