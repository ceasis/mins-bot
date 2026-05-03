package com.minsbot.skills.watch_file;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minsbot.agent.tools.EmailTools;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Watches single files for change. Three modes:
 * <ul>
 *   <li>{@code mtime}  — fire on last-modified-time flip (cheapest)</li>
 *   <li>{@code hash}   — fire when SHA-256 of contents differs from last seen</li>
 *   <li>{@code regex}  — fire when contents start matching a regex they didn't before
 *                        (or stop matching, going either direction)</li>
 * </ul>
 *
 * <p>Persists records as JSON under {@code ~/mins_bot_data/<storage-dir>/}. One
 * scheduled tick per {@code app.skills.watch-file.tick-interval-seconds}; each
 * record's {@code intervalSeconds} gates its own work so a 1-hour watcher
 * doesn't read the file every 30 s.
 */
@Service
public class WatchFileService {

    private static final Logger log = LoggerFactory.getLogger(WatchFileService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final WatchFileConfig.Properties cfg;
    private final EmailTools emailTools;

    private final Map<String, WatchFileEntry> entries = new ConcurrentHashMap<>();
    private final Map<String, Long> lastTickMs = new ConcurrentHashMap<>();
    private Path storageRoot;

    public WatchFileService(WatchFileConfig.Properties cfg, EmailTools emailTools) {
        this.cfg = cfg;
        this.emailTools = emailTools;
    }

    @PostConstruct
    void init() {
        if (!cfg.isEnabled()) { log.info("[WatchFile] disabled via config"); return; }
        storageRoot = Paths.get(System.getProperty("user.home"), "mins_bot_data")
                .resolve(cfg.getStorageDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(storageRoot);
            try (var stream = Files.list(storageRoot)) {
                stream.filter(p -> p.toString().endsWith(".json")).forEach(this::loadOne);
            }
            log.info("[WatchFile] loaded {} watcher(s) from {}", entries.size(), storageRoot);
        } catch (IOException e) {
            log.warn("[WatchFile] init failed: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void loadOne(Path p) {
        try {
            Map<String, Object> m = JSON.readValue(Files.readAllBytes(p), Map.class);
            WatchFileEntry e = WatchFileEntry.fromMap(m);
            if (e.id != null) entries.put(e.id, e);
        } catch (Exception ex) {
            log.warn("[WatchFile] could not read {}: {}", p, ex.getMessage());
        }
    }

    private void persist(WatchFileEntry e) {
        try {
            Path file = storageRoot.resolve(e.id + ".json");
            JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), e.toMap());
        } catch (Exception ex) {
            log.warn("[WatchFile] persist failed for {}: {}", e.id, ex.getMessage());
        }
    }

    // ─── Public API ──────────────────────────────────────────────────────

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (WatchFileEntry e : entries.values()) out.add(e.toMap());
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("createdAt"))));
        return out;
    }

    public WatchFileEntry create(WatchFileEntry e) {
        if (e.path == null || e.path.isBlank()) throw new IllegalArgumentException("path is required");
        Path target = Paths.get(e.path);
        if (!Files.isRegularFile(target)) {
            throw new IllegalArgumentException("file does not exist: " + e.path);
        }
        if (e.intervalSeconds < cfg.getMinIntervalSeconds()) {
            e.intervalSeconds = cfg.getMinIntervalSeconds();
        }
        if (e.id == null || e.id.isBlank()) e.id = "wf_" + System.currentTimeMillis();
        if (e.createdAt == null || e.createdAt.isBlank()) e.createdAt = Instant.now().toString();
        // Snapshot the initial state so the FIRST real tick doesn't fire spuriously.
        try {
            e.lastState = snapshot(e, target);
        } catch (Exception ex) {
            log.warn("[WatchFile] initial snapshot failed for {}: {}", e.id, ex.getMessage());
        }
        entries.put(e.id, e);
        persist(e);
        log.info("[WatchFile] created {} ({}, mode={}, every {}s)",
                e.id, e.path, e.mode, e.intervalSeconds);
        return e;
    }

    public boolean delete(String id) {
        WatchFileEntry e = entries.remove(id);
        if (e == null) return false;
        try { Files.deleteIfExists(storageRoot.resolve(id + ".json")); } catch (Exception ignored) {}
        return true;
    }

    /** Force a check now. Returns a status string. */
    public String checkNow(String id) {
        WatchFileEntry e = entries.get(id);
        if (e == null) return "not found";
        return tickOne(e, true);
    }

    // ─── Scheduler ───────────────────────────────────────────────────────

    @Scheduled(fixedDelayString = "${app.skills.watch-file.tick-interval-seconds:30}000")
    public void tick() {
        if (!cfg.isEnabled() || entries.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (WatchFileEntry e : entries.values()) {
            long last = lastTickMs.getOrDefault(e.id, 0L);
            if (now - last < e.intervalSeconds * 1000L) continue;
            lastTickMs.put(e.id, now);
            try { tickOne(e, false); }
            catch (Exception ex) { log.warn("[WatchFile] {} tick failed: {}", e.id, ex.getMessage()); }
        }
    }

    private String tickOne(WatchFileEntry e, boolean force) {
        e.lastCheckedAt = Instant.now().toString();
        Path target = Paths.get(e.path);
        if (!Files.isRegularFile(target)) {
            persist(e);
            return "missing";
        }
        String now;
        try { now = snapshot(e, target); }
        catch (Exception ex) {
            log.warn("[WatchFile] {} snapshot failed: {}", e.id, ex.getMessage());
            persist(e);
            return "error: " + ex.getMessage();
        }
        boolean changed = !Objects.equals(now, e.lastState);
        if (changed && !force) {
            String detail = "Mode: " + e.mode + "\nPrevious: " + truncate(e.lastState, 80)
                    + "\nCurrent:  " + truncate(now, 80);
            notify(e, detail);
            e.lastNotifiedAt = e.lastCheckedAt;
        }
        e.lastState = now;
        persist(e);
        return changed ? "changed" : "unchanged";
    }

    /** State snapshot used to detect change. Format depends on mode. */
    private String snapshot(WatchFileEntry e, Path target) throws Exception {
        return switch (e.mode == null ? "mtime" : e.mode.toLowerCase()) {
            case "hash"  -> "sha256:" + sha256(target);
            case "regex" -> matchesRegex(target, e.pattern) ? "matched" : "unmatched";
            default      -> "mtime:" + Files.getLastModifiedTime(target).toMillis();
        };
    }

    private String sha256(Path p) throws Exception {
        if (Files.size(p) > cfg.getMaxContentBytes()) {
            throw new IOException("file exceeds max-content-bytes (" + cfg.getMaxContentBytes() + ")");
        }
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(p)) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private boolean matchesRegex(Path p, String pattern) throws IOException {
        if (pattern == null || pattern.isBlank()) return false;
        if (Files.size(p) > cfg.getMaxContentBytes()) return false;
        String content = Files.readString(p, StandardCharsets.UTF_8);
        return Pattern.compile(pattern, Pattern.MULTILINE | Pattern.DOTALL).matcher(content).find();
    }

    private void notify(WatchFileEntry e, String detail) {
        String label = e.label != null && !e.label.isBlank() ? e.label : e.path;
        if (e.notifyEmail != null && !e.notifyEmail.isBlank()) {
            try {
                emailTools.sendEmail(e.notifyEmail,
                        "[Mins Bot] File changed: " + label,
                        "Watcher fired.\n\nLabel: " + label + "\nPath: " + e.path + "\n\n" + detail);
                log.info("[WatchFile] emailed {} for {}", e.notifyEmail, e.id);
            } catch (Exception ex) {
                log.warn("[WatchFile] email send failed for {}: {}", e.id, ex.getMessage());
            }
        }
        // Webhook delivery shares the existing watcher webhook contract — keep it inline,
        // simple text body works for ntfy.sh / Discord / generic endpoints.
        if (e.notifyWebhook != null && !e.notifyWebhook.isBlank()) {
            sendPlainWebhook(e.notifyWebhook, "📂 File changed: " + label + "\n" + detail);
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
        } catch (Exception e) {
            log.warn("[WatchFile] webhook send failed: {}", e.getMessage());
        }
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
