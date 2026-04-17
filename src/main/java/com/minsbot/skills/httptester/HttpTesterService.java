package com.minsbot.skills.httptester;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Service
public class HttpTesterService {

    private final HttpTesterConfig.HttpTesterProperties properties;
    private final HttpClient http;

    public HttpTesterService(HttpTesterConfig.HttpTesterProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public Map<String, Object> execute(String method, String url, Map<String, String> headers, String body) throws IOException, InterruptedException, URISyntaxException {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("url required");
        URI uri = new URI(url);
        List<String> allowed = properties.getAllowedHosts();
        if (!allowed.isEmpty() && uri.getHost() != null && !allowed.contains(uri.getHost())) {
            throw new IllegalArgumentException("host not in allowedHosts: " + uri.getHost());
        }

        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(properties.getTimeoutMs()));
        if (headers != null) headers.forEach(b::header);

        HttpRequest.BodyPublisher publisher = body == null || body.isEmpty()
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);

        String m = method == null ? "GET" : method.toUpperCase();
        switch (m) {
            case "GET" -> b.GET();
            case "POST" -> b.POST(publisher);
            case "PUT" -> b.PUT(publisher);
            case "DELETE" -> b.DELETE();
            case "PATCH" -> b.method("PATCH", publisher);
            case "HEAD" -> b.method("HEAD", HttpRequest.BodyPublishers.noBody());
            case "OPTIONS" -> b.method("OPTIONS", HttpRequest.BodyPublishers.noBody());
            default -> throw new IllegalArgumentException("unsupported method: " + method);
        }

        long start = System.currentTimeMillis();
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        long elapsed = System.currentTimeMillis() - start;

        String respBody = resp.body();
        boolean truncated = respBody != null && respBody.length() > properties.getMaxResponseBytes();
        if (truncated) respBody = respBody.substring(0, properties.getMaxResponseBytes());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("method", m);
        out.put("url", url);
        out.put("status", resp.statusCode());
        out.put("elapsedMs", elapsed);
        out.put("headers", resp.headers().map());
        out.put("body", respBody);
        if (truncated) out.put("bodyTruncated", true);
        out.put("bodySize", resp.body() == null ? 0 : resp.body().length());
        return out;
    }
}
