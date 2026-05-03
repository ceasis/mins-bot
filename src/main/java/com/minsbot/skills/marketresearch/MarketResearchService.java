package com.minsbot.skills.marketresearch;

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
 * Researches market news from user-supplied feeds (Yahoo Finance ticker RSS,
 * SEC EDGAR feeds, Reuters/Bloomberg, subreddit RSS like /r/stocks). Extracts
 * $TICKER mentions, applies a simple bullish/bearish lexicon, ranks signals.
 *
 * NOT trading advice — outputs research signals only. Does NOT auto-trade.
 */
@Service
public class MarketResearchService {

    private final MarketResearchConfig.MarketResearchProperties props;
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
    private static final Pattern TICKER = Pattern.compile("\\$([A-Z]{1,5})\\b");

    private static final Set<String> BULLISH = Set.of(
            "beat", "beats", "surge", "surges", "soar", "soars", "rally", "rallies", "upgrade",
            "upgraded", "buy", "outperform", "raised", "raise", "record", "high", "growth",
            "expansion", "approved", "breakthrough", "partnership", "acquired", "acquires");
    private static final Set<String> BEARISH = Set.of(
            "miss", "misses", "plunge", "plunges", "crash", "tumble", "tumbles", "downgrade",
            "downgraded", "sell", "underperform", "cut", "cuts", "lower", "loss", "losses",
            "fraud", "lawsuit", "investigation", "probe", "bankruptcy", "delisted", "warning");

    public MarketResearchService(MarketResearchConfig.MarketResearchProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    public Map<String, Object> run(List<String> tickers, List<String> sources, Integer maxResults) {
        if (tickers == null) tickers = List.of();
        if (sources == null) sources = List.of();
        int cap = maxResults == null ? props.getMaxResults() : Math.min(maxResults, props.getMaxResults());
        Set<String> tickerSet = new HashSet<>();
        for (String t : tickers) tickerSet.add(t.toUpperCase(Locale.ROOT).replace("$", ""));

        List<Map<String, Object>> findings = new ArrayList<>();
        List<Map<String, Object>> sourceReport = new ArrayList<>();
        Map<String, int[]> tickerSentiment = new HashMap<>(); // [bull, bear, mentions]
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
                    String snippet = item.getOrDefault("snippet", "").toString();
                    String full = title + " " + snippet;
                    String hay = full.toLowerCase(Locale.ROOT);

                    Set<String> foundTickers = new LinkedHashSet<>();
                    Matcher tm = TICKER.matcher(full);
                    while (tm.find()) foundTickers.add(tm.group(1));
                    // Also match plain uppercase tickers from filter set
                    for (String t : tickerSet) {
                        if (Pattern.compile("\\b" + Pattern.quote(t) + "\\b").matcher(full).find()) {
                            foundTickers.add(t);
                        }
                    }
                    if (!tickerSet.isEmpty()) {
                        boolean any = foundTickers.stream().anyMatch(tickerSet::contains);
                        if (!any) continue;
                    } else if (foundTickers.isEmpty()) {
                        continue;
                    }

                    int bull = 0, bear = 0;
                    List<String> bullHits = new ArrayList<>();
                    List<String> bearHits = new ArrayList<>();
                    for (String w : hay.split("[^a-z]+")) {
                        if (BULLISH.contains(w)) { bull++; bullHits.add(w); }
                        if (BEARISH.contains(w)) { bear++; bearHits.add(w); }
                    }
                    int sentiment = bull - bear;
                    int score = Math.abs(sentiment) + foundTickers.size();

                    for (String t : foundTickers) {
                        int[] s = tickerSentiment.computeIfAbsent(t, k -> new int[3]);
                        s[0] += bull; s[1] += bear; s[2] += 1;
                    }

                    item.put("score", score);
                    item.put("tickers", new ArrayList<>(foundTickers));
                    item.put("sentiment", sentiment > 0 ? "bullish" : sentiment < 0 ? "bearish" : "neutral");
                    item.put("sentimentScore", sentiment);
                    item.put("bullishWords", bullHits);
                    item.put("bearishWords", bearHits);
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

        List<Map<String, Object>> tickerSummary = new ArrayList<>();
        tickerSentiment.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue()[2], a.getValue()[2]))
                .limit(20)
                .forEach(e -> {
                    int[] s = e.getValue();
                    Map<String, Object> ts = new LinkedHashMap<>();
                    ts.put("ticker", e.getKey());
                    ts.put("mentions", s[2]);
                    ts.put("bullish", s[0]);
                    ts.put("bearish", s[1]);
                    ts.put("net", s[0] - s[1]);
                    tickerSummary.add(ts);
                });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skill", "marketresearch");
        result.put("disclaimer", "Research signals only — not trading advice.");
        result.put("watchedTickers", tickers);
        result.put("sourcesQueried", srcCount);
        result.put("totalSignals", findings.size());
        result.put("tickerSummary", tickerSummary);
        result.put("sources", sourceReport);
        result.put("findings", findings);
        return result;
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
            if (d.find()) item.put("snippet", truncate(stripHtml(clean(d.group(1))), 600));
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
