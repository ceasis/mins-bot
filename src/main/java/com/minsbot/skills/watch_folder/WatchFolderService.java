package com.minsbot.skills.watch_folder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minsbot.agent.tools.EmailTools;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * Watches folders for create / modify / delete events using Java NIO's
 * {@link WatchService}. Event-driven, so no polling — the OS notifies the
 * service when a file lands, vanishes, or is rewritten.
 *
 * <p>Each watcher runs on a dedicated daemon thread that {@code take()}s from
 * a {@link WatchService}. Recursive watchers register subfolders too and
 * auto-register newly-created subfolders mid-flight. Bursts (e.g. an editor
 * saving a file via tmp→rename) are debounced to one notification per
 * {@code app.skills.watch-folder.debounce-ms}.
 */
@Service
public class WatchFolderService {

    private static final Logger log = LoggerFactory.getLogger(WatchFolderService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final WatchFolderConfig.Properties cfg;
    private final EmailTools emailTools;

    private final Map<String, WatchFolderEntry> entries = new ConcurrentHashMap<>();
    private final Map<String, WatcherThread> threads = new ConcurrentHashMap<>();
    private Path storageRoot;

    public WatchFolderService(WatchFolderConfig.Properties cfg, EmailTools emailTools) {
        this.cfg = cfg;
        this.emailTools = emailTools;
    }

    @PostConstruct
    void init() {
        if (!cfg.isEnabled()) { log.info("[WatchFolder] disabled via config"); return; }
        storageRoot = Paths.get(System.getProperty("user.home"), "mins_bot_data")
                .resolve(cfg.getStorageDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(storageRoot);
            try (var stream = Files.list(storageRoot)) {
                stream.filter(p -> p.toString().endsWith(".json")).forEach(this::loadOne);
            }
            for (WatchFolderEntry e : entries.values()) startThread(e);
            log.info("[WatchFolder] started {} watcher(s) from {}", entries.size(), storageRoot);
        } catch (IOException e) {
            log.warn("[WatchFolder] init failed: {}", e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        for (WatcherThread t : threads.values()) t.stopWatch();
        threads.clear();
    }

    @SuppressWarnings("unchecked")
    private void loadOne(Path p) {
        try {
            Map<String, Object> m = JSON.readValue(Files.readAllBytes(p), Map.class);
            WatchFolderEntry e = WatchFolderEntry.fromMap(m);
            if (e.id != null) entries.put(e.id, e);
        } catch (Exception ex) {
            log.warn("[WatchFolder] could not read {}: {}", p, ex.getMessage());
        }
    }

    private void persist(WatchFolderEntry e) {
        try {
            Path file = storageRoot.resolve(e.id + ".json");
            JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), e.toMap());
        } catch (Exception ex) {
            log.warn("[WatchFolder] persist failed for {}: {}", e.id, ex.getMessage());
        }
    }

    // ─── Public API ──────────────────────────────────────────────────────

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (WatchFolderEntry e : entries.values()) out.add(e.toMap());
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("createdAt"))));
        return out;
    }

    public WatchFolderEntry create(WatchFolderEntry e) {
        if (e.path == null || e.path.isBlank()) throw new IllegalArgumentException("path is required");
        Path target = Paths.get(e.path);
        if (!Files.isDirectory(target)) {
            throw new IllegalArgumentException("folder does not exist: " + e.path);
        }
        if (e.id == null || e.id.isBlank()) e.id = "wd_" + System.currentTimeMillis();
        if (e.createdAt == null || e.createdAt.isBlank()) e.createdAt = Instant.now().toString();
        entries.put(e.id, e);
        persist(e);
        startThread(e);
        log.info("[WatchFolder] created {} ({}, mode={}, recursive={})",
                e.id, e.path, e.mode, e.recursive);
        return e;
    }

    public boolean delete(String id) {
        WatcherThread t = threads.remove(id);
        if (t != null) t.stopWatch();
        WatchFolderEntry e = entries.remove(id);
        if (e == null) return false;
        try { Files.deleteIfExists(storageRoot.resolve(id + ".json")); } catch (Exception ignored) {}
        return true;
    }

    private void startThread(WatchFolderEntry e) {
        WatcherThread t = new WatcherThread(e);
        threads.put(e.id, t);
        t.start();
    }

    // ─── Watcher thread ──────────────────────────────────────────────────

    private final class WatcherThread extends Thread {
        private final WatchFolderEntry entry;
        private volatile boolean running = true;
        private WatchService ws;
        private final Map<WatchKey, Path> keyToPath = new HashMap<>();
        private long lastNotifyMs = 0;

        WatcherThread(WatchFolderEntry entry) {
            super("watch-folder-" + entry.id);
            setDaemon(true);
            this.entry = entry;
        }

        void stopWatch() {
            running = false;
            if (ws != null) try { ws.close(); } catch (Exception ignored) {}
        }

        @Override
        public void run() {
            Path root = Paths.get(entry.path);
            try {
                ws = root.getFileSystem().newWatchService();
                register(root);
                if (entry.recursive) {
                    try (var stream = Files.walk(root)) {
                        stream.filter(Files::isDirectory).forEach(d -> {
                            if (!d.equals(root)) register(d);
                        });
                    }
                }
                while (running) {
                    WatchKey key = ws.poll(2, TimeUnit.SECONDS);
                    if (key == null) continue;
                    Path dir = keyToPath.get(key);
                    if (dir == null) { key.reset(); continue; }
                    for (WatchEvent<?> ev : key.pollEvents()) {
                        WatchEvent.Kind<?> kind = ev.kind();
                        if (kind == OVERFLOW) continue;
                        Path rel = (Path) ev.context();
                        Path full = dir.resolve(rel);
                        handleEvent(kind, full);
                        // Auto-register newly-created subfolders when recursive.
                        if (entry.recursive && kind == ENTRY_CREATE && Files.isDirectory(full)) {
                            register(full);
                        }
                    }
                    if (!key.reset()) {
                        keyToPath.remove(key);
                        if (keyToPath.isEmpty()) break;
                    }
                }
            } catch (ClosedWatchServiceException | InterruptedException ignored) {
                // shutdown path
            } catch (Exception e) {
                log.warn("[WatchFolder] {} thread crashed: {}", entry.id, e.getMessage());
            }
        }

        private void register(Path dir) {
            try {
                WatchKey k = dir.register(ws, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
                keyToPath.put(k, dir);
            } catch (IOException e) {
                log.warn("[WatchFolder] {} could not register {}: {}", entry.id, dir, e.getMessage());
            }
        }

        private void handleEvent(WatchEvent.Kind<?> kind, Path full) {
            String fileName = full.getFileName().toString();
            String mode = entry.mode == null ? "any" : entry.mode.toLowerCase();
            String kindStr = kind == ENTRY_CREATE ? "create"
                    : kind == ENTRY_DELETE ? "delete"
                    : kind == ENTRY_MODIFY ? "modify" : "other";
            // Mode gate.
            if (!"any".equals(mode) && !mode.equals(kindStr)) return;
            // Filename glob filter.
            if (entry.filter != null && !entry.filter.isBlank()
                    && !FileSystems.getDefault().getPathMatcher("glob:" + entry.filter)
                            .matches(full.getFileName())) {
                return;
            }
            // Debounce burst events.
            long now = System.currentTimeMillis();
            if (now - lastNotifyMs < cfg.getDebounceMs()) return;
            lastNotifyMs = now;

            String summary = kindStr + " — " + fileName;
            entry.lastEvent = summary;
            entry.lastCheckedAt = Instant.now().toString();
            entry.lastNotifiedAt = entry.lastCheckedAt;
            persist(entry);
            log.info("[WatchFolder] {} {}", entry.id, summary);
            notify(summary, full);
        }

        private void notify(String summary, Path full) {
            String label = entry.label != null && !entry.label.isBlank() ? entry.label : entry.path;
            if (entry.notifyEmail != null && !entry.notifyEmail.isBlank()) {
                try {
                    emailTools.sendEmail(entry.notifyEmail,
                            "[Mins Bot] Folder change: " + label,
                            "Watcher fired.\n\nLabel: " + label + "\nFolder: " + entry.path
                                    + "\nEvent: " + summary + "\nFull path: " + full);
                } catch (Exception e) {
                    log.warn("[WatchFolder] email send failed for {}: {}", entry.id, e.getMessage());
                }
            }
            if (entry.notifyWebhook != null && !entry.notifyWebhook.isBlank()) {
                sendPlainWebhook(entry.notifyWebhook,
                        "📁 Folder change: " + label + "\n" + summary + "\n" + full);
            }
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
            log.warn("[WatchFolder] webhook send failed: {}", e.getMessage());
        }
    }
}
