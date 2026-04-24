package com.minsbot.skills.watcher.adapters;

import com.minsbot.skills.watcher.Watcher;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Generic HTTP watcher for sites without bot protection.
 *
 * target is a regex. If the regex matches the page body -> in-stock, else out-of-stock.
 * Useful for simple "in stock" / "notify me" text detection.
 */
@Component
public class GenericHttpAdapter implements WatcherAdapter {

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public String name() { return "generic-http"; }

    @Override
    public CheckResult check(Watcher w) throws Exception {
        if (w.target == null || w.target.isBlank()) return CheckResult.error("target regex is empty");
        HttpRequest req = HttpRequest.newBuilder(URI.create(w.url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) return CheckResult.error("HTTP " + resp.statusCode());

        Pattern p = Pattern.compile(w.target, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        boolean match = p.matcher(resp.body()).find();
        return match
                ? CheckResult.inStock("regex matched")
                : CheckResult.outOfStock("regex did not match");
    }
}
