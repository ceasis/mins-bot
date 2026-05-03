package com.minsbot.skills.gighunter;

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
 * Researches freelance/gig opportunities from user-supplied source URLs
 * (RSS feeds, job board search pages, etc.). Scores each item by keyword
 * overlap with the user's skills and ranks the top matches.
 *
 * Typical sources: Upwork RSS feeds, Fiverr search HTML, Reddit r/forhire RSS,
 * RemoteOK feed, We Work Remotely feed, Hacker News "who's hiring".
 */
@Service
public class GigHunterService {

    private final GigHunterConfig.GigHunterProperties props;
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

    public GigHunterService(GigHunterConfig.GigHunterProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public Map<String, Object> run(List<String> keywords, List<String> sources,
                                   List<String> excludeKeywords, Integer maxResults) {
        if (keywords == null) keywords = List.of();
        if (sources == null) sources = List.of();
        if (excludeKeywords == null) excludeKeywords = List.of();
        int cap = maxResults == null ? props.getMaxResults() : Math.min(maxResults, props.getMaxResults());

        List<String> kwLower = keywords.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        List<String> exLower = excludeKeywords.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();

        List<Map<String, Object>> findings = new ArrayList<>();
        List<Map<String, Object>> sourceReport = new ArrayList<>();
        int srcCount = Math.min(sources.size(), props.getMaxSources());

        for (int i = 0; i < srcCount; i++) {
            String src = sources.get(i);
            Map<String, Object> srcInfo = new LinkedHashMap<>();
            srcInfo.put("url", src);
            try {
                String body = fetch(src);
                List<Map<String, Object>> items = parseFeed(body, src);
                if (items.isEmpty()) items = parseHtmlLinks(body, src);
                int matched = 0;
                for (Map<String, Object> item : items) {
                    String hay = (item.getOrDefault("title", "") + " " + item.getOrDefault("snippet", ""))
                            .toString().toLowerCase(Locale.ROOT);
                    if (!exLower.isEmpty() && exLower.stream().anyMatch(hay::contains)) continue;
                    int score = 0;
                    List<String> matchedKw = new ArrayList<>();
                    for (String kw : kwLower) {
                        if (kw.isBlank()) continue;
                        if (hay.contains(kw)) { score++; matchedKw.add(kw); }
                    }
                    if (score == 0 && !kwLower.isEmpty()) continue;
                    item.put("score", score);
                    item.put("matchedKeywords", matchedKw);
                    item.put("source", src);
                    findings.add(item);
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

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skill", "gighunter");
        result.put("keywords", keywords);
        result.put("sourcesQueried", srcCount);
        result.put("totalMatches", findings.size());
        result.put("sources", sourceReport);
        result.put("findings", findings);
        return result;
    }

    private String fetch(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .timeout(Duration.ofMillis(props.getTimeoutMs()))
                .header("User-Agent", props.getUserAgent())
                .header("Accept", "application/rss+xml, application/atom+xml, text/html, */*")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) throw new RuntimeException("HTTP " + resp.statusCode());
        String body = resp.body();
        if (body.length() > props.getMaxFetchBytes()) body = body.substring(0, props.getMaxFetchBytes());
        return body;
    }

    static List<Map<String, Object>> parseFeed(String body, String sourceUrl) {
        List<Map<String, Object>> items = new ArrayList<>();
        Matcher m = RSS_ITEM.matcher(body);
        boolean atom = false;
        if (!m.find()) {
            m = ATOM_ENTRY.matcher(body);
            atom = true;
        } else {
            m.reset();
        }
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
            if (item.containsKey("title") || item.containsKey("snippet")) items.add(item);
        }
        return items;
    }

    static List<Map<String, Object>> parseHtmlLinks(String body, String sourceUrl) {
        List<Map<String, Object>> items = new ArrayList<>();
        Pattern a = Pattern.compile("<a\\s[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m = a.matcher(body);
        while (m.find() && items.size() < 200) {
            String href = m.group(1);
            String text = stripHtml(m.group(2)).trim();
            if (text.length() < 10 || href.startsWith("#")) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", truncate(text, 200));
            item.put("url", absolutize(href, sourceUrl));
            item.put("snippet", "");
            items.add(item);
        }
        return items;
    }

    private static String absolutize(String href, String base) {
        try {
            URI b = URI.create(base);
            return b.resolve(href).toString();
        } catch (Exception e) { return href; }
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
