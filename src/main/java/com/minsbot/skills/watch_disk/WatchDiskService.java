package com.minsbot.skills.watch_disk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minsbot.agent.tools.EmailTools;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Periodically checks free space on a disk volume and fires when it drops
 * below a configured threshold. Edge-triggered: fires once on the ok→low
 * flip, again on low→ok recovery, never on every tick while still low.
 */
@Service
public class WatchDiskService {

    private static final Logger log = LoggerFactory.getLogger(WatchDiskService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final WatchDiskConfig.Properties cfg;
    private final EmailTools emailTools;

    private final Map<String, WatchDiskEntry> entries = new ConcurrentHashMap<>();
    private final Map<String, Long> lastTickMs = new ConcurrentHashMap<>();
    private Path storageRoot;

    public WatchDiskService(WatchDiskConfig.Properties cfg, EmailTools emailTools) {
        this.cfg = cfg;
        this.emailTools = emailTools;
    }

    @PostConstruct
    void init() {
        if (!cfg.isEnabled()) { log.info("[WatchDisk] disabled"); return; }
        storageRoot = Paths.get(System.getProperty("user.home"), "mins_bot_data")
                .resolve(cfg.getStorageDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(storageRoot);
            try (var s = Files.list(storageRoot)) {
                s.filter(p -> p.toString().endsWith(".json")).forEach(this::loadOne);
            }
            log.info("[WatchDisk] loaded {} watcher(s)", entries.size());
        } catch (IOException e) {
            log.warn("[WatchDisk] init failed: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void loadOne(Path p) {
        try {
            Map<String, Object> m = JSON.readValue(Files.readAllBytes(p), Map.class);
            WatchDiskEntry e = WatchDiskEntry.fromMap(m);
            if (e.id != null) entries.put(e.id, e);
        } catch (Exception ex) { log.warn("[WatchDisk] read {} failed: {}", p, ex.getMessage()); }
    }

    private void persist(WatchDiskEntry e) {
        try { JSON.writerWithDefaultPrettyPrinter().writeValue(storageRoot.resolve(e.id + ".json").toFile(), e.toMap()); }
        catch (Exception ex) { log.warn("[WatchDisk] persist {} failed: {}", e.id, ex.getMessage()); }
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (WatchDiskEntry e : entries.values()) out.add(e.toMap());
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("createdAt"))));
        return out;
    }

    public WatchDiskEntry create(WatchDiskEntry e) {
        if (e.path == null || e.path.isBlank()) throw new IllegalArgumentException("path is required");
        File f = new File(e.path);
        if (f.getTotalSpace() <= 0) throw new IllegalArgumentException("path has no volume info: " + e.path);
        if (e.intervalSeconds < cfg.getMinIntervalSeconds()) e.intervalSeconds = cfg.getMinIntervalSeconds();
        if (e.id == null || e.id.isBlank()) e.id = "wsk_" + System.currentTimeMillis();
        if (e.createdAt == null || e.createdAt.isBlank()) e.createdAt = Instant.now().toString();
        // Initial state snapshot — prevents firing on the very first tick.
        e.lastFreeGb = f.getFreeSpace() / 1_073_741_824.0;
        e.lastTotalGb = f.getTotalSpace() / 1_073_741_824.0;
        e.lastState = e.lastFreeGb < e.freeBelowGb ? "low" : "ok";
        entries.put(e.id, e);
        persist(e);
        log.info("[WatchDisk] created {} ({} threshold={}GB, every {}s)",
                e.id, e.path, e.freeBelowGb, e.intervalSeconds);
        return e;
    }

    public boolean delete(String id) {
        WatchDiskEntry e = entries.remove(id);
        if (e == null) return false;
        try { Files.deleteIfExists(storageRoot.resolve(id + ".json")); } catch (Exception ignored) {}
        return true;
    }

    public String checkNow(String id) {
        WatchDiskEntry e = entries.get(id);
        if (e == null) return "not found";
        return tickOne(e, true);
    }

    @Scheduled(fixedDelayString = "${app.skills.watch-disk.tick-interval-seconds:60}000")
    public void tick() {
        if (!cfg.isEnabled() || entries.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (WatchDiskEntry e : entries.values()) {
            long last = lastTickMs.getOrDefault(e.id, 0L);
            if (now - last < e.intervalSeconds * 1000L) continue;
            lastTickMs.put(e.id, now);
            try { tickOne(e, false); } catch (Exception ex) { log.warn("[WatchDisk] {} tick failed: {}", e.id, ex.getMessage()); }
        }
    }

    private String tickOne(WatchDiskEntry e, boolean force) {
        e.lastCheckedAt = Instant.now().toString();
        File f = new File(e.path);
        long total = f.getTotalSpace();
        long free = f.getFreeSpace();
        if (total <= 0) { persist(e); return "missing"; }
        double freeGb = free / 1_073_741_824.0;
        double totalGb = total / 1_073_741_824.0;
        e.lastFreeGb = freeGb;
        e.lastTotalGb = totalGb;
        String now = freeGb < e.freeBelowGb ? "low" : "ok";
        boolean flipped = !Objects.equals(now, e.lastState);
        if ((flipped || force) && !"".equals(e.lastState)) {
            String headline = "low".equals(now)
                    ? String.format("⚠ Free space LOW: %.1f GB free of %.1f GB on %s (threshold: %.1f GB)",
                            freeGb, totalGb, e.path, e.freeBelowGb)
                    : String.format("✅ Free space recovered: %.1f GB free of %.1f GB on %s",
                            freeGb, totalGb, e.path);
            if (flipped) {
                notify(e, headline);
                e.lastNotifiedAt = e.lastCheckedAt;
            }
        }
        e.lastState = now;
        persist(e);
        return now + String.format(" (%.1f / %.1f GB)", freeGb, totalGb);
    }

    private void notify(WatchDiskEntry e, String detail) {
        String label = e.label != null && !e.label.isBlank() ? e.label : e.path;
        if (e.notifyEmail != null && !e.notifyEmail.isBlank()) {
            try {
                emailTools.sendEmail(e.notifyEmail, "[Mins Bot] Disk: " + label, detail);
            } catch (Exception ex) { log.warn("[WatchDisk] email failed for {}: {}", e.id, ex.getMessage()); }
        }
        if (e.notifyWebhook != null && !e.notifyWebhook.isBlank()) {
            sendPlainWebhook(e.notifyWebhook, detail);
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
        } catch (Exception e) { log.warn("[WatchDisk] webhook failed: {}", e.getMessage()); }
    }
}
