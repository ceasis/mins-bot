package com.minsbot.skills.competitor;

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
 * Fetches competitor home pages and extracts: <title>, meta description,
 * meta keywords, H1, primary CTAs, links to pricing/about/blog pages, and
 * top words. Aggregates a positioning report across all sites.
 */
@Service
public class CompetitorService {

    private final CompetitorConfig.CompetitorProperties props;
    private final HttpClient http;

    private static final Pattern TITLE = Pattern.compile(
            "<title\\b[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern META_DESC = Pattern.compile(
            "<meta\\b[^>]*name\\s*=\\s*\"description\"[^>]*content\\s*=\\s*\"([^\"]*)\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern META_KEYWORDS = Pattern.compile(
            "<meta\\b[^>]*name\\s*=\\s*\"keywords\"[^>]*content\\s*=\\s*\"([^\"]*)\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OG_DESC = Pattern.compile(
            "<meta\\b[^>]*property\\s*=\\s*\"og:description\"[^>]*content\\s*=\\s*\"([^\"]*)\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern H1 = Pattern.compile(
            "<h1\\b[^>]*>(.*?)</h1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern A_HREF = Pattern.compile(
            "<a\\s[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HTML_TAGS = Pattern.compile("<[^>]+>");
    private static final Pattern WORD = Pattern.compile("[a-zA-Z]{4,}");

    private static final Set<String> CTA_HINTS = Set.of(
            "sign up", "get started", "try free", "start free", "buy", "book",
            "demo", "learn more", "subscribe", "download", "join", "schedule");

    private static final Set<String> KEY_PAGE_HINTS = Set.of(
            "pricing", "plans", "features", "about", "blog", "case-studies",
            "customers", "docs", "api", "integrations", "contact", "careers");

    private static final Set<String> STOPWORDS = Set.of(
            "this", "that", "with", "from", "your", "have", "more", "what", "when",
            "they", "them", "their", "will", "would", "could", "should", "than", "then",
            "into", "about", "which", "where", "been", "were", "also", "such", "some",
            "other", "only", "even", "just", "like", "well", "very", "make", "made",
            "much", "many", "most", "best", "good");

    public CompetitorService(CompetitorConfig.CompetitorProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    public Map<String, Object> analyze(List<String> sites) {
        if (sites == null) sites = List.of();
        int cap = Math.min(sites.size(), props.getMaxSites());

        List<Map<String, Object>> reports = new ArrayList<>();
        Map<String, Integer> sharedWords = new HashMap<>();
        Map<String, Integer> sharedCtas = new HashMap<>();

        for (int i = 0; i < cap; i++) {
            String site = sites.get(i);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("site", site);
            try {
                String body = fetch(site);
                String title = firstGroup(TITLE, body);
                String desc = firstAttr(META_DESC, body);
                if (desc.isBlank()) desc = firstAttr(OG_DESC, body);
                String keywords = firstAttr(META_KEYWORDS, body);
                String h1 = stripHtml(firstGroup(H1, body));

                List<String> ctas = new ArrayList<>();
                List<Map<String, String>> keyPages = new ArrayList<>();
                Matcher am = A_HREF.matcher(body);
                int aCount = 0;
                while (am.find() && aCount < 500) {
                    aCount++;
                    String href = am.group(1);
                    String text = stripHtml(am.group(2)).trim();
                    String low = text.toLowerCase(Locale.ROOT);
                    if (CTA_HINTS.stream().anyMatch(low::contains) && text.length() < 60) {
                        ctas.add(text);
                        sharedCtas.merge(low, 1, Integer::sum);
                    }
                    String hrefLow = href.toLowerCase(Locale.ROOT);
                    for (String h : KEY_PAGE_HINTS) {
                        if (hrefLow.contains("/" + h)) {
                            keyPages.add(Map.of("type", h, "url", absolutize(href, site), "text", text));
                            break;
                        }
                    }
                }

                String text = stripHtml(body).toLowerCase(Locale.ROOT);
                Matcher wm = WORD.matcher(text);
                Map<String, Integer> wc = new HashMap<>();
                while (wm.find()) {
                    String w = wm.group();
                    if (STOPWORDS.contains(w)) continue;
                    wc.merge(w, 1, Integer::sum);
                }
                List<Map<String, Object>> topWords = new ArrayList<>();
                wc.entrySet().stream()
                        .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                        .limit(20)
                        .forEach(e -> {
                            topWords.add(Map.of("word", e.getKey(), "count", e.getValue()));
                            sharedWords.merge(e.getKey(), 1, Integer::sum);
                        });

                r.put("title", title);
                r.put("metaDescription", desc);
                r.put("metaKeywords", keywords);
                r.put("h1", h1);
                r.put("ctas", new ArrayList<>(new LinkedHashSet<>(ctas)));
                r.put("keyPages", keyPages);
                r.put("topWords", topWords);
                r.put("ok", true);
            } catch (Exception e) {
                r.put("ok", false);
                r.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            reports.add(r);
        }

        List<Map<String, Object>> sharedWordList = new ArrayList<>();
        sharedWords.entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(25)
                .forEach(e -> sharedWordList.add(Map.of("word", e.getKey(), "siteCount", e.getValue())));

        List<Map<String, Object>> sharedCtaList = new ArrayList<>();
        sharedCtas.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(15)
                .forEach(e -> sharedCtaList.add(Map.of("cta", e.getKey(), "occurrences", e.getValue())));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skill", "competitor");
        result.put("sitesAnalyzed", reports.size());
        result.put("sharedVocabulary", sharedWordList);
        result.put("commonCtas", sharedCtaList);
        result.put("reports", reports);
        return result;
    }

    private String fetch(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .timeout(Duration.ofMillis(props.getTimeoutMs()))
                .header("User-Agent", props.getUserAgent())
                .header("Accept", "text/html,*/*").GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) throw new RuntimeException("HTTP " + resp.statusCode());
        String body = resp.body();
        if (body.length() > props.getMaxFetchBytes()) body = body.substring(0, props.getMaxFetchBytes());
        return body;
    }

    private static String firstGroup(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? clean(m.group(1)) : "";
    }
    private static String firstAttr(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? clean(m.group(1)) : "";
    }
    private static String absolutize(String href, String base) {
        try { return URI.create(base).resolve(href).toString(); } catch (Exception e) { return href; }
    }
    private static String stripHtml(String s) {
        if (s == null) return "";
        return decode(HTML_TAGS.matcher(s).replaceAll(" ")).replaceAll("\\s+", " ").trim();
    }
    private static String clean(String s) {
        if (s == null) return "";
        return decode(s.replace("<![CDATA[", "").replace("]]>", "").trim());
    }
    private static String decode(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'").replace("&nbsp;", " ");
    }
}
