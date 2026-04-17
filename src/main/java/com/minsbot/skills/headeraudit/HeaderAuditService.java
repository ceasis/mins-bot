package com.minsbot.skills.headeraudit;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Service
public class HeaderAuditService {

    private final HeaderAuditConfig.HeaderAuditProperties properties;
    private final HttpClient http;

    private static final List<String[]> RECOMMENDED = List.of(
            new String[]{"strict-transport-security", "HSTS — enforces HTTPS. Recommended: max-age>=31536000; includeSubDomains"},
            new String[]{"content-security-policy", "CSP — restricts sources of scripts/styles/images etc."},
            new String[]{"x-frame-options", "Clickjacking protection (or frame-ancestors in CSP)"},
            new String[]{"x-content-type-options", "Prevents MIME sniffing. Should be 'nosniff'"},
            new String[]{"referrer-policy", "Controls Referer header. Recommended: strict-origin-when-cross-origin"},
            new String[]{"permissions-policy", "Restricts browser features (geolocation, camera, etc.)"},
            new String[]{"cross-origin-opener-policy", "COOP — isolates browsing context group"},
            new String[]{"cross-origin-resource-policy", "CORP — controls cross-origin resource loading"},
            new String[]{"cross-origin-embedder-policy", "COEP — requires CORP for embedded resources"}
    );

    private static final List<String[]> LEAKY = List.of(
            new String[]{"server", "Reveals web server software"},
            new String[]{"x-powered-by", "Reveals framework/language"},
            new String[]{"x-aspnet-version", "Reveals ASP.NET version"},
            new String[]{"x-aspnetmvc-version", "Reveals ASP.NET MVC version"}
    );

    public HeaderAuditService(HeaderAuditConfig.HeaderAuditProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public Map<String, Object> audit(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                .header("User-Agent", "MinsBot-HeaderAudit/1.0")
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<Void> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception headFailed) {
            HttpRequest get = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                    .header("User-Agent", "MinsBot-HeaderAudit/1.0")
                    .GET().build();
            resp = http.send(get, HttpResponse.BodyHandlers.discarding());
        }

        Map<String, List<String>> headers = new LinkedHashMap<>();
        resp.headers().map().forEach((k, v) -> headers.put(k.toLowerCase(), v));

        List<Map<String, Object>> present = new ArrayList<>();
        List<Map<String, Object>> missing = new ArrayList<>();
        for (String[] rec : RECOMMENDED) {
            String name = rec[0];
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("header", name);
            entry.put("purpose", rec[1]);
            if (headers.containsKey(name)) {
                entry.put("value", headers.get(name));
                present.add(entry);
            } else {
                missing.add(entry);
            }
        }

        List<Map<String, Object>> leaks = new ArrayList<>();
        for (String[] leaky : LEAKY) {
            if (headers.containsKey(leaky[0])) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("header", leaky[0]);
                entry.put("value", headers.get(leaky[0]));
                entry.put("concern", leaky[1]);
                leaks.add(entry);
            }
        }

        int score = 100 - missing.size() * 10 - leaks.size() * 5;
        score = Math.max(0, Math.min(100, score));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("url", url);
        result.put("httpStatus", resp.statusCode());
        result.put("score", score);
        result.put("recommendedPresent", present);
        result.put("recommendedMissing", missing);
        result.put("informationLeaks", leaks);
        return result;
    }
}
