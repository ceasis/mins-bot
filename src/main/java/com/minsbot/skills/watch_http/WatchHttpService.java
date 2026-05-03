package com.minsbot.skills.watch_http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minsbot.agent.tools.EmailTools;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Periodically GETs a URL; alerts on the up→down or down→up flip. Optional
 * "slow above N ms" alert when the page loads but is dragging. HEAD would be
 * cheaper but many sites 405 it; GET is the universal sledgehammer.
 */
@Service
public class WatchHttpService {

    private static final Logger log = LoggerFactory.getLogger(WatchHttpService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final WatchHttpConfig.Properties cfg;
    private final EmailTools emailTools;

    private final Map<String, WatchHttpEntry> entries = new ConcurrentHashMap<>();
    private final Map<String, Long> lastTickMs = new ConcurrentHashMap<>();
    private Path storageRoot;

    public WatchHttpService(WatchHttpConfig.Properties cfg, EmailTools emailTools) {
        this.cfg = cfg;
        this.emailTools = emailTools;
    }

    @PostConstruct
    void init() {
        if (!cfg.isEnabled()) { log.info("[WatchHttp] disabled"); return; }
        storageRoot = Paths.get(System.getProperty("user.home"), "mins_bot_data")
                .resolve(cfg.getStorageDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(storageRoot);
            try (var s = Files.list(storageRoot)) {
                s.filter(p -> p.toString().endsWith(".json")).forEach(this::loadOne);
            }
            log.info("[WatchHttp] loaded {} watcher(s)", entries.size());
        } catch (IOException e) {
            log.warn("[WatchHttp] init failed: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void loadOne(Path p) {
        try {
            Map<String, Object> m = JSON.readValue(Files.readAllBytes(p), Map.class);
            WatchHttpEntry e = WatchHttpEntry.fromMap(m);
            if (e.id != null) entries.put(e.id, e);
        } catch (Exception ex) { log.warn("[WatchHttp] read {} failed: {}", p, ex.getMessage()); }
    }

    private void persist(WatchHttpEntry e) {
        try { JSON.writerWithDefaultPrettyPrinter().writeValue(storageRoot.resolve(e.id + ".json").toFile(), e.toMap()); }
        catch (Exception ex) { log.warn("[WatchHttp] persist failed: {}", ex.getMessage()); }
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (WatchHttpEntry e : entries.values()) out.add(e.toMap());
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("createdAt"))));
        return out;
    }

    public WatchHttpEntry create(WatchHttpEntry e) {
        if (e.url == null || e.url.isBlank()) throw new IllegalArgumentException("url is required");
        if (!e.url.startsWith("http://") && !e.url.startsWith("https://")) {
            throw new IllegalArgumentException("url must start with http:// or https://");
        }
        if (e.intervalSeconds < cfg.getMinIntervalSeconds()) e.intervalSeconds = cfg.getMinIntervalSeconds();
        if (e.id == null || e.id.isBlank()) e.id = "wh_" + System.currentTimeMillis();
        if (e.createdAt == null || e.createdAt.isBlank()) e.createdAt = Instant.now().toString();
        entries.put(e.id, e);
        persist(e);
        log.info("[WatchHttp] created {} ({}, every {}s)", e.id, e.url, e.intervalSeconds);
        return e;
    }

    public boolean delete(String id) {
        WatchHttpEntry e = entries.remove(id);
        if (e == null) return false;
        try { Files.deleteIfExists(storageRoot.resolve(id + ".json")); } catch (Exception ignored) {}
        return true;
    }

    public String checkNow(String id) {
        WatchHttpEntry e = entries.get(id);
        if (e == null) return "not found";
        return tickOne(e, true);
    }

    @Scheduled(fixedDelayString = "${app.skills.watch-http.tick-interval-seconds:60}000")
    public void tick() {
        if (!cfg.isEnabled() || entries.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (WatchHttpEntry e : entries.values()) {
            long last = lastTickMs.getOrDefault(e.id, 0L);
            if (now - last < e.intervalSeconds * 1000L) continue;
            lastTickMs.put(e.id, now);
            try { tickOne(e, false); } catch (Exception ex) { log.warn("[WatchHttp] {} tick failed: {}", e.id, ex.getMessage()); }
        }
    }

    private String tickOne(WatchHttpEntry e, boolean force) {
        e.lastCheckedAt = Instant.now().toString();
        long start = System.currentTimeMillis();
        int code = 0;
        boolean up = false;
        String error = null;
        try {
            HttpClient.Builder b = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(cfg.getRequestTimeoutSeconds()));
            b.followRedirects(e.followRedirects ? HttpClient.Redirect.NORMAL : HttpClient.Redirect.NEVER);
            HttpClient http = b.build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(e.url))
                    .timeout(Duration.ofSeconds(cfg.getRequestTimeoutSeconds()))
                    .header("User-Agent", "MinsBot-Watcher/1.0")
                    .GET().build();
            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            code = resp.statusCode();
            up = code >= 200 && code < 400;
        } catch (Exception ex) {
            error = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }
        long latency = System.currentTimeMillis() - start;
        e.lastStatusCode = code;
        e.lastLatencyMs = latency;
        String now = up ? "up" : "down";
        boolean flipped = !Objects.equals(now, e.lastState);
        boolean slow = up && e.slowAboveMs > 0 && latency > e.slowAboveMs;
        if (flipped && !"".equals(e.lastState)) {
            String detail = up
                    ? String.format("✅ Back UP — HTTP %d in %d ms — %s", code, latency, e.url)
                    : String.format("⚠ DOWN — %s%s — %s",
                            code > 0 ? ("HTTP " + code) : "no response",
                            error != null ? " (" + error + ")" : "",
                            e.url);
            notify(e, detail);
            e.lastNotifiedAt = e.lastCheckedAt;
        } else if (slow && !"slow".equals(e.lastState)) {
            // Track slow as a separate edge so we don't spam on every tick.
            String detail = String.format("🐢 Slow — HTTP %d in %d ms (threshold %d) — %s",
                    code, latency, e.slowAboveMs, e.url);
            notify(e, detail);
            e.lastNotifiedAt = e.lastCheckedAt;
            e.lastState = "slow";
            persist(e);
            return "slow (" + latency + " ms)";
        }
        if (force && !flipped) {
            // Manual check — surface current state without sending email.
            log.info("[WatchHttp] {} manual check: {} (HTTP {} in {} ms)",
                    e.id, now, code, latency);
        }
        e.lastState = now;
        persist(e);
        return now + " (HTTP " + code + " in " + latency + " ms)";
    }

    private void notify(WatchHttpEntry e, String detail) {
        String label = e.label != null && !e.label.isBlank() ? e.label : e.url;
        if (e.notifyEmail != null && !e.notifyEmail.isBlank()) {
            try { emailTools.sendEmail(e.notifyEmail, "[Mins Bot] HTTP: " + label, detail); }
            catch (Exception ex) { log.warn("[WatchHttp] email failed: {}", ex.getMessage()); }
        }
        if (e.notifyWebhook != null && !e.notifyWebhook.isBlank()) {
            sendPlainWebhook(e.notifyWebhook, detail);
        }
    }

    private static void sendPlainWebhook(String url, String text) {
        try {
            var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            var req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(text))
                    .build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) { log.warn("[WatchHttp] webhook failed: {}", e.getMessage()); }
    }
}
