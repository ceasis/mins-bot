package com.minsbot.skills.utmbuilder;

import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class UtmBuilderService {

    private static final List<String> UTM_FIELDS = List.of(
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id"
    );

    public Map<String, Object> build(String baseUrl, Map<String, String> utms) {
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("url required");
        URI uri;
        try { uri = URI.create(baseUrl); } catch (Exception e) { throw new IllegalArgumentException("invalid url"); }

        Map<String, String> params = new LinkedHashMap<>();
        if (uri.getRawQuery() != null) {
            for (String pair : uri.getRawQuery().split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) params.put(pair.substring(0, eq), pair.substring(eq + 1));
                else if (!pair.isEmpty()) params.put(pair, "");
            }
        }

        List<String> added = new ArrayList<>();
        List<String> missingRequired = new ArrayList<>();
        for (String field : UTM_FIELDS) {
            String v = utms.get(field);
            if (v != null && !v.isBlank()) {
                params.put(field, URLEncoder.encode(v.trim(), StandardCharsets.UTF_8));
                added.add(field);
            }
        }
        for (String req : List.of("utm_source", "utm_medium", "utm_campaign")) {
            if (!params.containsKey(req)) missingRequired.add(req);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(uri.getScheme()).append("://").append(uri.getAuthority());
        if (uri.getRawPath() != null) sb.append(uri.getRawPath());
        if (!params.isEmpty()) {
            sb.append('?');
            boolean first = true;
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (!first) sb.append('&');
                sb.append(e.getKey()).append('=').append(e.getValue());
                first = false;
            }
        }
        if (uri.getRawFragment() != null) sb.append('#').append(uri.getRawFragment());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("input", baseUrl);
        out.put("output", sb.toString());
        out.put("addedFields", added);
        out.put("warnings", missingRequired.isEmpty()
                ? List.of()
                : List.of("Missing recommended: " + String.join(", ", missingRequired)));
        return out;
    }

    public Map<String, Object> parse(String url) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("url required");
        URI uri;
        try { uri = URI.create(url); } catch (Exception e) { throw new IllegalArgumentException("invalid url"); }
        Map<String, String> out = new LinkedHashMap<>();
        if (uri.getRawQuery() != null) {
            for (String pair : uri.getRawQuery().split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    String key = pair.substring(0, eq);
                    if (key.startsWith("utm_")) {
                        out.put(key, URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                    }
                }
            }
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("url", url);
        resp.put("utms", out);
        return resp;
    }
}
