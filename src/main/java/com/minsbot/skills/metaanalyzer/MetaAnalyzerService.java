package com.minsbot.skills.metaanalyzer;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MetaAnalyzerService {

    private final MetaAnalyzerConfig.MetaAnalyzerProperties properties;
    private final HttpClient http;

    private static final Pattern TITLE = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern META = Pattern.compile("<meta\\s+([^>]+?)\\s*/?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK = Pattern.compile("<link\\s+([^>]+?)\\s*/?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR = Pattern.compile("(\\w[\\w-]*)\\s*=\\s*\"([^\"]*)\"|(\\w[\\w-]*)\\s*=\\s*'([^']*)'", Pattern.CASE_INSENSITIVE);
    private static final Pattern H1 = Pattern.compile("<h1[^>]*>(.*?)</h1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    public MetaAnalyzerService(MetaAnalyzerConfig.MetaAnalyzerProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public Map<String, Object> analyze(String url) throws IOException, InterruptedException {
        String html = fetch(url);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("url", url);
        result.put("contentLength", html.length());

        String title = firstGroup(TITLE, html);
        Map<String, String> ogTags = new LinkedHashMap<>();
        Map<String, String> twitterTags = new LinkedHashMap<>();
        Map<String, String> nameTags = new LinkedHashMap<>();
        String canonical = null;

        Matcher m = META.matcher(html);
        while (m.find()) {
            Map<String, String> attrs = parseAttrs(m.group(1));
            String content = attrs.getOrDefault("content", "");
            String property = attrs.get("property");
            String name = attrs.get("name");
            if (property != null && property.startsWith("og:")) {
                ogTags.put(property, content);
            } else if (name != null && name.toLowerCase().startsWith("twitter:")) {
                twitterTags.put(name.toLowerCase(), content);
            } else if (name != null) {
                nameTags.put(name.toLowerCase(), content);
            }
        }

        Matcher lm = LINK.matcher(html);
        while (lm.find()) {
            Map<String, String> attrs = parseAttrs(lm.group(1));
            if ("canonical".equalsIgnoreCase(attrs.get("rel"))) {
                canonical = attrs.get("href");
            }
        }

        String description = nameTags.get("description");
        String robots = nameTags.get("robots");
        String h1 = stripTags(firstGroup(H1, html));

        result.put("title", title == null ? null : title.trim());
        result.put("description", description);
        result.put("canonical", canonical);
        result.put("robots", robots);
        result.put("h1", h1);
        result.put("openGraph", ogTags);
        result.put("twitter", twitterTags);
        result.put("otherMeta", nameTags);

        result.put("issues", evaluate(title, description, canonical, ogTags, h1));
        return result;
    }

    private List<String> evaluate(String title, String description, String canonical, Map<String, String> og, String h1) {
        List<String> issues = new ArrayList<>();
        if (title == null || title.isBlank()) issues.add("Missing <title>");
        else {
            int len = title.trim().length();
            if (len < 30) issues.add("Title is short (" + len + " chars, aim 50-60)");
            if (len > 60) issues.add("Title is long (" + len + " chars, aim 50-60)");
        }
        if (description == null || description.isBlank()) issues.add("Missing meta description");
        else {
            int len = description.length();
            if (len < 70) issues.add("Description is short (" + len + " chars, aim 150-160)");
            if (len > 160) issues.add("Description is long (" + len + " chars, aim 150-160)");
        }
        if (canonical == null || canonical.isBlank()) issues.add("Missing rel=canonical");
        if (og.isEmpty()) issues.add("No OpenGraph tags found");
        else {
            if (!og.containsKey("og:title")) issues.add("Missing og:title");
            if (!og.containsKey("og:description")) issues.add("Missing og:description");
            if (!og.containsKey("og:image")) issues.add("Missing og:image");
        }
        if (h1 == null || h1.isBlank()) issues.add("Missing <h1>");
        return issues;
    }

    private String fetch(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", properties.getUserAgent())
                .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + resp.statusCode() + " from " + url);
        }
        String body = resp.body();
        return body.length() > properties.getMaxBytes() ? body.substring(0, properties.getMaxBytes()) : body;
    }

    private static Map<String, String> parseAttrs(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = ATTR.matcher(raw);
        while (m.find()) {
            String key = m.group(1) != null ? m.group(1) : m.group(3);
            String val = m.group(2) != null ? m.group(2) : m.group(4);
            if (key != null) out.put(key.toLowerCase(), val == null ? "" : val);
        }
        return out;
    }

    private static String firstGroup(Pattern p, String input) {
        Matcher m = p.matcher(input);
        return m.find() ? m.group(1) : null;
    }

    private static String stripTags(String s) {
        return s == null ? null : s.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
    }
}
