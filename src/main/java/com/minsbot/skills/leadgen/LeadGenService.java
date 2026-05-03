package com.minsbot.skills.leadgen;

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
 * Researches business leads from user-supplied sources (subreddit RSS for
 * "/r/forhire", "/r/slavelabour", company blog feeds, classified RSS, BBB,
 * Indeed RSS for posts mentioning a service gap, etc.). Detects buying-intent
 * signals and extracts contact info.
 *
 * Output: ranked leads with detected intent + contact emails / handles.
 */
@Service
public class LeadGenService {

    private final LeadGenConfig.LeadGenProperties props;
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
    private static final Pattern HTML_TAGS = Pattern.compile("<[^>]+>");

    private static final Pattern INTENT = Pattern.compile(
            "\\b(looking\\s+for|need(?:ed)?|hiring|seeking|wanted|recommend(?:ation)?|anyone\\s+know|help\\s+with|in\\s+search\\s+of|require|require[ds]|outsourc(?:e|ing)|freelancer|consultant|agency|hire\\s+someone|pay(?:ing)?|budget|will\\s+pay)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern HANDLE = Pattern.compile("(?:^|\\s)@([A-Za-z0-9_]{2,30})");
    private static final Pattern PHONE = Pattern.compile(
            "(?:\\+?\\d{1,3}[\\s.-])?\\(?\\d{3}\\)?[\\s.-]?\\d{3,4}[\\s.-]?\\d{4}");
    private static final Pattern URGENT = Pattern.compile(
            "\\b(urgent|asap|today|this\\s+week|tight\\s+deadline|immediate)\\b", Pattern.CASE_INSENSITIVE);

    public LeadGenService(LeadGenConfig.LeadGenProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    public Map<String, Object> run(List<String> serviceKeywords, List<String> sources,
                                   Integer maxResults) {
        if (serviceKeywords == null) serviceKeywords = List.of();
        if (sources == null) sources = List.of();
        int cap = maxResults == null ? props.getMaxResults() : Math.min(maxResults, props.getMaxResults());
        List<String> kwLower = serviceKeywords.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();

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
                    String title = item.getOrDefault("title", "").toString();
                    String snippet = item.getOrDefault("snippet", "").toString();
                    String hay = (title + " " + snippet).toLowerCase(Locale.ROOT);

                    int kwScore = 0;
                    List<String> matchedKw = new ArrayList<>();
                    for (String kw : kwLower) {
                        if (kw.isBlank()) continue;
                        if (hay.contains(kw)) { kwScore++; matchedKw.add(kw); }
                    }
                    if (kwScore == 0 && !kwLower.isEmpty()) continue;

                    int intentScore = 0;
                    List<String> intentHits = new ArrayList<>();
                    Matcher in = INTENT.matcher(hay);
                    while (in.find()) { intentScore++; intentHits.add(in.group(1)); }
                    if (intentScore == 0) continue; // no intent = not a lead

                    boolean urgent = URGENT.matcher(hay).find();
                    String full = title + " " + snippet;
                    List<String> emails = matchAll(EMAIL, full);
                    List<String> handles = matchAll(HANDLE, full);
                    List<String> phones = matchAll(PHONE, full);

                    int contactBoost = (emails.isEmpty() ? 0 : 3)
                            + (handles.isEmpty() ? 0 : 1)
                            + (phones.isEmpty() ? 0 : 2);
                    int score = kwScore * 2 + intentScore + contactBoost + (urgent ? 2 : 0);

                    item.put("score", score);
                    item.put("matchedServices", matchedKw);
                    item.put("intentSignals", intentHits);
                    item.put("urgent", urgent);
                    item.put("emails", emails);
                    item.put("handles", handles);
                    item.put("phones", phones);
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
        result.put("skill", "leadgen");
        result.put("services", serviceKeywords);
        result.put("sourcesQueried", srcCount);
        result.put("totalLeads", findings.size());
        result.put("sources", sourceReport);
        result.put("findings", findings);
        return result;
    }

    private static List<String> matchAll(Pattern p, String s) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(s);
        while (m.find()) {
            String v = m.groupCount() >= 1 && m.group(1) != null ? m.group(1) : m.group();
            if (!out.contains(v)) out.add(v);
        }
        return out;
    }

    private String fetch(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .timeout(Duration.ofMillis(props.getTimeoutMs()))
                .header("User-Agent", props.getUserAgent())
                .header("Accept", "application/rss+xml, application/atom+xml, text/html, */*").GET().build();
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
