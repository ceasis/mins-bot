package com.minsbot.skills.arbiscout;

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
 * Researches arbitrage / reselling opportunities from user-supplied marketplace
 * feeds and listings (Craigslist RSS, eBay search RSS, FB Marketplace exports,
 * subreddit RSS, etc.). Extracts prices, flags potential underpricing signals
 * (urgency words, "must sell", "below market"), and ranks candidates.
 */
@Service
public class ArbiScoutService {

    private final ArbiScoutConfig.ArbiScoutProperties props;
    private final HttpClient http;

    private static final Pattern RSS_ITEM = Pattern.compile(
            "<item\\b[^>]*>(.*?)</item>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile(
            "<title\\b[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern LINK_RSS = Pattern.compile(
            "<link\\b[^>]*>(.*?)</link>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern DESC = Pattern.compile(
            "<(?:description|summary|content)\\b[^>]*>(.*?)</(?:description|summary|content)>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern PRICE = Pattern.compile(
            "(?:USD|\\$|£|€)\\s?(\\d{1,3}(?:[,.]\\d{3})*(?:\\.\\d{1,2})?)");
    private static final Pattern URGENCY = Pattern.compile(
            "\\b(must\\s+sell|moving|asap|urgent|firm|obo|negotiable|cash|today\\s+only|quick\\s+sale|below\\s+market|fire\\s+sale|leaving|estate)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CONDITION = Pattern.compile(
            "\\b(new|like\\s+new|brand\\s+new|sealed|unopened|mint|excellent|barely\\s+used)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_TAGS = Pattern.compile("<[^>]+>");

    public ArbiScoutService(ArbiScoutConfig.ArbiScoutProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    public Map<String, Object> run(List<String> keywords, List<String> sources,
                                   Double maxPrice, Double minPrice, Integer maxResults) {
        if (keywords == null) keywords = List.of();
        if (sources == null) sources = List.of();
        int cap = maxResults == null ? props.getMaxResults() : Math.min(maxResults, props.getMaxResults());
        List<String> kwLower = keywords.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();

        List<Map<String, Object>> findings = new ArrayList<>();
        List<Map<String, Object>> sourceReport = new ArrayList<>();
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
                    String hay = (item.getOrDefault("title", "") + " " + item.getOrDefault("snippet", ""))
                            .toString().toLowerCase(Locale.ROOT);
                    int kwScore = 0;
                    List<String> matchedKw = new ArrayList<>();
                    for (String kw : kwLower) {
                        if (kw.isBlank()) continue;
                        if (hay.contains(kw)) { kwScore++; matchedKw.add(kw); }
                    }
                    if (kwScore == 0 && !kwLower.isEmpty()) continue;

                    Double price = extractPrice(hay);
                    if (price != null) {
                        if (maxPrice != null && price > maxPrice) continue;
                        if (minPrice != null && price < minPrice) continue;
                    }
                    int signals = 0;
                    List<String> signalList = new ArrayList<>();
                    Matcher u = URGENCY.matcher(hay);
                    while (u.find()) { signals++; signalList.add(u.group(1)); }
                    Matcher c = CONDITION.matcher(hay);
                    while (c.find()) { signals++; signalList.add(c.group(1)); }

                    int score = kwScore * 2 + signals + (price != null ? 1 : 0);
                    item.put("price", price);
                    item.put("matchedKeywords", matchedKw);
                    item.put("signals", signalList);
                    item.put("score", score);
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
        result.put("skill", "arbiscout");
        result.put("keywords", keywords);
        result.put("priceFilter", Map.of("min", minPrice == null ? "" : minPrice, "max", maxPrice == null ? "" : maxPrice));
        result.put("sourcesQueried", srcCount);
        result.put("totalMatches", findings.size());
        result.put("sources", sourceReport);
        result.put("findings", findings);
        return result;
    }

    private static Double extractPrice(String text) {
        Matcher m = PRICE.matcher(text);
        if (!m.find()) return null;
        try {
            String n = m.group(1).replace(",", "");
            return Double.parseDouble(n);
        } catch (Exception e) { return null; }
    }

    private String fetch(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .timeout(Duration.ofMillis(props.getTimeoutMs()))
                .header("User-Agent", props.getUserAgent())
                .header("Accept", "application/rss+xml, text/html, */*").GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) throw new RuntimeException("HTTP " + resp.statusCode());
        String body = resp.body();
        if (body.length() > props.getMaxFetchBytes()) body = body.substring(0, props.getMaxFetchBytes());
        return body;
    }

    private static List<Map<String, Object>> parseFeed(String body) {
        List<Map<String, Object>> items = new ArrayList<>();
        Matcher m = RSS_ITEM.matcher(body);
        while (m.find()) {
            String chunk = m.group(1);
            Map<String, Object> item = new LinkedHashMap<>();
            Matcher t = TITLE.matcher(chunk);
            if (t.find()) item.put("title", clean(t.group(1)));
            Matcher l = LINK_RSS.matcher(chunk);
            if (l.find()) item.put("url", clean(l.group(1)));
            Matcher d = DESC.matcher(chunk);
            if (d.find()) item.put("snippet", truncate(stripHtml(clean(d.group(1))), 400));
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
