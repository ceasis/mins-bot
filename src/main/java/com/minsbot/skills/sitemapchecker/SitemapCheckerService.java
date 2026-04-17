package com.minsbot.skills.sitemapchecker;

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
public class SitemapCheckerService {

    private final SitemapCheckerConfig.SitemapCheckerProperties properties;
    private final HttpClient http;

    private static final Pattern LOC = Pattern.compile("<loc>\\s*(.*?)\\s*</loc>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SITEMAP_INDEX = Pattern.compile("<sitemapindex", Pattern.CASE_INSENSITIVE);

    public SitemapCheckerService(SitemapCheckerConfig.SitemapCheckerProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public Map<String, Object> check(String sitemapUrl, boolean checkStatus) throws IOException, InterruptedException {
        String body = fetch(sitemapUrl);
        boolean isIndex = SITEMAP_INDEX.matcher(body).find();
        List<String> locs = new ArrayList<>();
        Matcher m = LOC.matcher(body);
        while (m.find()) {
            String loc = decodeEntities(m.group(1).trim());
            if (!loc.isEmpty()) locs.add(loc);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sitemap", sitemapUrl);
        result.put("type", isIndex ? "sitemap-index" : "urlset");
        result.put("totalUrls", locs.size());
        result.put("sampleUrls", locs.subList(0, Math.min(10, locs.size())));

        Set<String> dupes = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        for (String u : locs) if (!seen.add(u)) dupes.add(u);
        result.put("duplicateCount", dupes.size());
        result.put("duplicates", new ArrayList<>(dupes).subList(0, Math.min(10, dupes.size())));

        if (checkStatus) {
            int limit = Math.min(locs.size(), properties.getMaxUrlsToCheck());
            List<Map<String, Object>> statuses = new ArrayList<>();
            int ok = 0, broken = 0;
            for (int i = 0; i < limit; i++) {
                String u = locs.get(i);
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("url", u);
                try {
                    HttpRequest req = HttpRequest.newBuilder().uri(URI.create(u))
                            .method("HEAD", HttpRequest.BodyPublishers.noBody())
                            .timeout(Duration.ofMillis(properties.getTimeoutMs())).build();
                    HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
                    int code = resp.statusCode();
                    s.put("status", code);
                    s.put("ok", code / 100 == 2);
                    if (code / 100 == 2) ok++; else broken++;
                } catch (Exception e) {
                    s.put("status", -1);
                    s.put("ok", false);
                    s.put("error", e.getClass().getSimpleName());
                    broken++;
                }
                statuses.add(s);
            }
            result.put("checkedCount", limit);
            result.put("okCount", ok);
            result.put("brokenCount", broken);
            result.put("checked", statuses);
        }
        return result;
    }

    private String fetch(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                .header("User-Agent", "MinsBot-SitemapChecker/1.0")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) throw new IOException("HTTP " + resp.statusCode());
        String body = resp.body();
        if (body.length() > properties.getMaxSitemapBytes()) {
            body = body.substring(0, properties.getMaxSitemapBytes());
        }
        return body;
    }

    private static String decodeEntities(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'");
    }
}
