package com.minsbot.skills.hibpcheck;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class HibpCheckService {

    private final HibpCheckConfig.HibpCheckProperties properties;
    private final HttpClient http;

    public HibpCheckService(HibpCheckConfig.HibpCheckProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getTimeoutMs()))
                .build();
    }

    public Map<String, Object> check(String password) throws IOException, InterruptedException, NoSuchAlgorithmException {
        if (password == null || password.isEmpty()) throw new IllegalArgumentException("password required");
        String sha1 = sha1Hex(password).toUpperCase();
        String prefix = sha1.substring(0, 5);
        String suffix = sha1.substring(5);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(properties.getApiBase() + prefix))
                .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                .header("Add-Padding", "true")
                .header("User-Agent", "MinsBot-HibpCheck/1.0")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) throw new IOException("HIBP API returned HTTP " + resp.statusCode());

        long count = 0;
        for (String line : resp.body().split("\\r?\\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String hashSuffix = line.substring(0, colon).trim();
            if (hashSuffix.equalsIgnoreCase(suffix)) {
                try { count = Long.parseLong(line.substring(colon + 1).trim()); } catch (NumberFormatException ignored) {}
                break;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("prefix", prefix);
        result.put("pwned", count > 0);
        result.put("occurrences", count);
        result.put("advice", count > 0
                ? "Password appears in known breach corpora. DO NOT use it."
                : "Password not found in HIBP breach corpora.");
        return result;
    }

    private static String sha1Hex(String s) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
