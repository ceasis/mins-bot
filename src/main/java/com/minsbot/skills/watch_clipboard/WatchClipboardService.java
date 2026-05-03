package com.minsbot.skills.watch_clipboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minsbot.agent.tools.EmailTools;
import jakarta.annotation.PostConstruct;
import javafx.application.Platform;
import javafx.scene.input.Clipboard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Polls the system clipboard via JavaFX. Two modes by pattern:
 * <ul>
 *   <li>empty pattern — fire on ANY clipboard text change (no regex match)</li>
 *   <li>non-empty pattern — fire only when the new clipboard content matches
 *       the pattern (e.g. {@code \\b1Z[0-9A-Z]{16}\\b} for UPS tracking numbers)</li>
 * </ul>
 *
 * <p>Clipboard reads MUST run on the JavaFX Application Thread. We hop there
 * via {@code Platform.runLater} + {@link CountDownLatch} so a slow FX thread
 * doesn't pile up Spring scheduler ticks.
 */
@Service
public class WatchClipboardService {

    private static final Logger log = LoggerFactory.getLogger(WatchClipboardService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final WatchClipboardConfig.Properties cfg;
    private final EmailTools emailTools;

    private final Map<String, WatchClipboardEntry> entries = new ConcurrentHashMap<>();
    private final Map<String, Long> lastTickMs = new ConcurrentHashMap<>();
    /** The last clipboard text we observed — shared across watchers so a single
     *  clipboard read per tick covers them all, no matter how many records exist. */
    private volatile String lastSeenClipboard = null;
    private Path storageRoot;

    public WatchClipboardService(WatchClipboardConfig.Properties cfg, EmailTools emailTools) {
        this.cfg = cfg;
        this.emailTools = emailTools;
    }

    @PostConstruct
    void init() {
        if (!cfg.isEnabled()) { log.info("[WatchClipboard] disabled"); return; }
        storageRoot = Paths.get(System.getProperty("user.home"), "mins_bot_data")
                .resolve(cfg.getStorageDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(storageRoot);
            try (var s = Files.list(storageRoot)) {
                s.filter(p -> p.toString().endsWith(".json")).forEach(this::loadOne);
            }
            log.info("[WatchClipboard] loaded {} watcher(s)", entries.size());
        } catch (IOException e) {
            log.warn("[WatchClipboard] init failed: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void loadOne(Path p) {
        try {
            Map<String, Object> m = JSON.readValue(Files.readAllBytes(p), Map.class);
            WatchClipboardEntry e = WatchClipboardEntry.fromMap(m);
            if (e.id != null) entries.put(e.id, e);
        } catch (Exception ex) { log.warn("[WatchClipboard] read {} failed: {}", p, ex.getMessage()); }
    }

    private void persist(WatchClipboardEntry e) {
        try { JSON.writerWithDefaultPrettyPrinter().writeValue(storageRoot.resolve(e.id + ".json").toFile(), e.toMap()); }
        catch (Exception ex) { log.warn("[WatchClipboard] persist failed: {}", ex.getMessage()); }
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (WatchClipboardEntry e : entries.values()) out.add(e.toMap());
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("createdAt"))));
        return out;
    }

    public WatchClipboardEntry create(WatchClipboardEntry e) {
        if (e.intervalSeconds < cfg.getMinIntervalSeconds()) e.intervalSeconds = cfg.getMinIntervalSeconds();
        if (e.pattern != null && !e.pattern.isBlank()) {
            try { Pattern.compile(e.pattern); }
            catch (Exception ex) { throw new IllegalArgumentException("invalid regex: " + ex.getMessage()); }
        }
        if (e.id == null || e.id.isBlank()) e.id = "wc_" + System.currentTimeMillis();
        if (e.createdAt == null || e.createdAt.isBlank()) e.createdAt = Instant.now().toString();
        entries.put(e.id, e);
        persist(e);
        log.info("[WatchClipboard] created {} (pattern={}, every {}s)",
                e.id, e.pattern == null ? "(any change)" : e.pattern, e.intervalSeconds);
        return e;
    }

    public boolean delete(String id) {
        WatchClipboardEntry e = entries.remove(id);
        if (e == null) return false;
        try { Files.deleteIfExists(storageRoot.resolve(id + ".json")); } catch (Exception ignored) {}
        return true;
    }

    @Scheduled(fixedDelayString = "${app.skills.watch-clipboard.poll-interval-seconds:2}000")
    public void tick() {
        if (!cfg.isEnabled() || entries.isEmpty()) return;
        // One clipboard read per tick — shared across all watchers.
        String current = readClipboardText();
        if (current == null) return;
        boolean changed = !Objects.equals(current, lastSeenClipboard);
        if (!changed) return;
        lastSeenClipboard = current;

        long now = System.currentTimeMillis();
        for (WatchClipboardEntry e : entries.values()) {
            long last = lastTickMs.getOrDefault(e.id, 0L);
            if (now - last < e.intervalSeconds * 1000L) continue;
            lastTickMs.put(e.id, now);
            try { evaluate(e, current); }
            catch (Exception ex) { log.warn("[WatchClipboard] {} eval failed: {}", e.id, ex.getMessage()); }
        }
    }

    private void evaluate(WatchClipboardEntry e, String content) {
        boolean matched;
        if (e.pattern == null || e.pattern.isBlank()) {
            matched = true; // any change
        } else {
            int flags = e.caseInsensitive ? Pattern.CASE_INSENSITIVE : 0;
            matched = Pattern.compile(e.pattern, flags).matcher(content).find();
        }
        if (!matched) return;
        e.lastMatchedAt = Instant.now().toString();
        e.lastSnippet = content.length() <= 120 ? content : content.substring(0, 120) + "…";
        e.lastNotifiedAt = e.lastMatchedAt;
        persist(e);
        log.info("[WatchClipboard] {} matched: {}", e.id, e.lastSnippet);
        notify(e, "Clipboard match — " + (e.label != null ? e.label : "(unnamed)") + "\n\n" + e.lastSnippet);
    }

    /** Read clipboard on the FX thread. 1s timeout — falls back to null (caller skips). */
    private static String readClipboardText() {
        AtomicReference<String> out = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Runnable r = () -> {
            try {
                Clipboard cb = Clipboard.getSystemClipboard();
                if (cb.hasString()) out.set(cb.getString());
            } catch (Exception ignored) {}
            finally { latch.countDown(); }
        };
        if (Platform.isFxApplicationThread()) {
            r.run();
        } else {
            try { Platform.runLater(r); }
            catch (IllegalStateException notStarted) { return null; }
            try {
                if (!latch.await(1, TimeUnit.SECONDS)) return null;
            } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
        }
        return out.get();
    }

    private void notify(WatchClipboardEntry e, String detail) {
        String label = e.label != null && !e.label.isBlank() ? e.label : "Clipboard match";
        if (e.notifyEmail != null && !e.notifyEmail.isBlank()) {
            try { emailTools.sendEmail(e.notifyEmail, "[Mins Bot] " + label, detail); }
            catch (Exception ex) { log.warn("[WatchClipboard] email failed: {}", ex.getMessage()); }
        }
        if (e.notifyWebhook != null && !e.notifyWebhook.isBlank()) {
            sendPlainWebhook(e.notifyWebhook, "📋 " + detail);
        }
    }

    private static void sendPlainWebhook(String url, String text) {
        try {
            var http = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5)).build();
            var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(text))
                    .build();
            http.send(req, java.net.http.HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) { log.warn("[WatchClipboard] webhook failed: {}", e.getMessage()); }
    }
}
