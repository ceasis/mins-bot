package com.minsbot.release;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * Polls a manifest URL (e.g. https://mins.io/version.json) that returns:
 *   { "version": "1.0.3", "url": "https://...", "notes": "..." }
 * Compares against {@code app.version} and, if newer, exposes the info to the
 * frontend via {@link ReleaseController} which shows a "Update available" banner.
 */
@Service
public class UpdateCheckService {

    private static final Logger log = LoggerFactory.getLogger(UpdateCheckService.class);

    @Value("${app.version:0.0.0}")
    private String currentVersion;

    @Value("${app.update.manifest-url:}")
    private String manifestUrl;

    @Value("${app.update.enabled:true}")
    private volatile boolean enabled;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile UpdateInfo latest;
    private volatile Instant lastCheck;
    private volatile String lastError;

    @PostConstruct
    public void onStart() {
        // Non-blocking: run shortly after startup.
        new Thread(this::check, "update-check-startup").start();
    }

    @Scheduled(fixedDelayString = "PT24H", initialDelayString = "PT1H")
    public void scheduled() { check(); }

    public synchronized void check() {
        if (!enabled || manifestUrl == null || manifestUrl.isBlank()) return;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(manifestUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "mins-bot/" + currentVersion)
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            lastCheck = Instant.now();
            if (resp.statusCode() != 200) {
                lastError = "HTTP " + resp.statusCode();
                return;
            }
            JsonNode json = mapper.readTree(resp.body());
            String ver = json.path("version").asText("");
            String url = json.path("url").asText("");
            String notes = json.path("notes").asText("");
            if (!ver.isBlank() && isNewer(ver, currentVersion)) {
                latest = new UpdateInfo(ver, url, notes);
                log.info("Update available: {} (current {})", ver, currentVersion);
            } else {
                latest = null;
            }
            lastError = null;
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.debug("Update check failed: {}", lastError);
        }
    }

    public UpdateInfo available() { return latest; }
    public String currentVersion() { return currentVersion; }
    public Instant lastCheck() { return lastCheck; }
    public String lastError() { return lastError; }

    /** Semver-ish comparison. Non-numeric / malformed versions fall back to string compare. */
    static boolean isNewer(String remote, String current) {
        try {
            String[] r = remote.split("[.\\-+]");
            String[] c = current.split("[.\\-+]");
            int n = Math.max(r.length, c.length);
            for (int i = 0; i < n; i++) {
                int ri = i < r.length ? parseOr(r[i], 0) : 0;
                int ci = i < c.length ? parseOr(c[i], 0) : 0;
                if (ri != ci) return ri > ci;
            }
            return false;
        } catch (Exception e) {
            return remote.compareTo(current) > 0;
        }
    }

    private static int parseOr(String s, int dflt) {
        try { return Integer.parseInt(s); } catch (Exception e) { return dflt; }
    }

    public record UpdateInfo(String version, String url, String notes) {}
}
