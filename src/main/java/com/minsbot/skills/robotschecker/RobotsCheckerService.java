package com.minsbot.skills.robotschecker;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Service
public class RobotsCheckerService {

    private final RobotsCheckerConfig.RobotsCheckerProperties properties;
    private final HttpClient http;

    public RobotsCheckerService(RobotsCheckerConfig.RobotsCheckerProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public Map<String, Object> parse(String robotsUrl) throws IOException, InterruptedException {
        String body = fetch(robotsUrl);
        return parseContent(body, robotsUrl);
    }

    public Map<String, Object> check(String robotsUrl, String path, String userAgent) throws IOException, InterruptedException {
        String body = fetch(robotsUrl);
        Map<String, Object> parsed = parseContent(body, robotsUrl);
        Map<String, Object> result = new LinkedHashMap<>(parsed);
        result.put("testedPath", path);
        result.put("testedUserAgent", userAgent == null ? "*" : userAgent);
        result.put("allowed", isAllowed(body, path, userAgent));
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseContent(String body, String url) {
        Map<String, List<String>> disallows = new LinkedHashMap<>();
        Map<String, List<String>> allows = new LinkedHashMap<>();
        List<String> sitemaps = new ArrayList<>();
        Long crawlDelay = null;

        String currentUa = null;
        for (String raw : body.split("\\r?\\n")) {
            String line = raw.replaceAll("#.*$", "").trim();
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).trim().toLowerCase();
            String val = line.substring(colon + 1).trim();
            switch (key) {
                case "user-agent" -> currentUa = val;
                case "disallow" -> {
                    if (currentUa != null) disallows.computeIfAbsent(currentUa, k -> new ArrayList<>()).add(val);
                }
                case "allow" -> {
                    if (currentUa != null) allows.computeIfAbsent(currentUa, k -> new ArrayList<>()).add(val);
                }
                case "sitemap" -> sitemaps.add(val);
                case "crawl-delay" -> {
                    try { crawlDelay = Long.parseLong(val); } catch (Exception ignored) {}
                }
                default -> {}
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", url);
        out.put("userAgents", new ArrayList<>(new LinkedHashSet<>(disallows.keySet().stream().toList())));
        out.put("disallow", disallows);
        out.put("allow", allows);
        out.put("sitemaps", sitemaps);
        if (crawlDelay != null) out.put("crawlDelay", crawlDelay);
        out.put("sizeBytes", body.length());
        return out;
    }

    private boolean isAllowed(String body, String path, String userAgent) {
        String ua = (userAgent == null || userAgent.isBlank()) ? "*" : userAgent;
        List<String[]> groupsForUa = groupRules(body, ua);
        if (groupsForUa.isEmpty()) groupsForUa = groupRules(body, "*");

        int bestAllowLen = -1;
        int bestDisallowLen = -1;
        for (String[] rule : groupsForUa) {
            String directive = rule[0];
            String pattern = rule[1];
            if (pattern.isEmpty()) {
                if ("disallow".equals(directive)) continue;
            }
            if (matches(pattern, path)) {
                if ("allow".equals(directive) && pattern.length() > bestAllowLen) bestAllowLen = pattern.length();
                if ("disallow".equals(directive) && pattern.length() > bestDisallowLen) bestDisallowLen = pattern.length();
            }
        }
        return bestDisallowLen < 0 || bestAllowLen >= bestDisallowLen;
    }

    private List<String[]> groupRules(String body, String ua) {
        List<String[]> rules = new ArrayList<>();
        boolean inGroup = false;
        for (String raw : body.split("\\r?\\n")) {
            String line = raw.replaceAll("#.*$", "").trim();
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).trim().toLowerCase();
            String val = line.substring(colon + 1).trim();
            if ("user-agent".equals(key)) {
                inGroup = val.equalsIgnoreCase(ua);
            } else if (inGroup && ("allow".equals(key) || "disallow".equals(key))) {
                rules.add(new String[]{key, val});
            }
        }
        return rules;
    }

    private static boolean matches(String pattern, String path) {
        if (pattern.isEmpty()) return false;
        String p = pattern.replace("$", "\\$").replace(".", "\\.").replace("?", "\\?").replace("*", ".*");
        if (pattern.endsWith("$")) {
            return path.matches("^" + p);
        }
        return path.startsWith(pattern.replaceAll("\\*.*", "")) || path.matches("^" + p + ".*");
    }

    private String fetch(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                .header("User-Agent", "MinsBot-RobotsChecker/1.0")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) throw new IOException("HTTP " + resp.statusCode());
        return resp.body();
    }
}
