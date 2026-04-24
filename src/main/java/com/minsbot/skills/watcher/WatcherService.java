package com.minsbot.skills.watcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minsbot.skills.watcher.adapters.WatcherAdapter;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

@Service
public class WatcherService {

    private static final Logger log = LoggerFactory.getLogger(WatcherService.class);

    private final WatcherConfig.WatcherProperties properties;
    private final WatcherNotifier notifier;
    private final Map<String, WatcherAdapter> adapters;
    private final ObjectMapper mapper = new ObjectMapper();

    private Thread poller;
    private volatile boolean running;

    public WatcherService(WatcherConfig.WatcherProperties properties,
                          WatcherNotifier notifier,
                          List<WatcherAdapter> adapterList) {
        this.properties = properties;
        this.notifier = notifier;
        Map<String, WatcherAdapter> byName = new HashMap<>();
        for (WatcherAdapter a : adapterList) byName.put(a.name(), a);
        this.adapters = byName;
    }

    @PostConstruct
    public void start() {
        if (!properties.isEnabled()) return;
        running = true;
        poller = new Thread(this::tickLoop, "watcher-poller");
        poller.setDaemon(true);
        poller.start();
        log.info("[Watcher] Poller started. Adapters: {}", adapters.keySet());
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (poller != null) poller.interrupt();
    }

    public Watcher create(Watcher w) throws IOException {
        if (w.url == null || w.url.isBlank()) throw new IllegalArgumentException("url required");
        if (w.adapter == null || !adapters.containsKey(w.adapter)) {
            throw new IllegalArgumentException("unknown adapter: " + w.adapter + " (available: " + adapters.keySet() + ")");
        }
        String normUrl = normalizeUrl(w.url);
        w.url = normUrl;

        // Adapter-specific URL sanity. nike-ph expects a product page — reject bare domain / category URLs.
        if ("nike-ph".equals(w.adapter)) {
            if (!normUrl.contains("/t/") || !normUrl.contains("nike.com")) {
                throw new IllegalArgumentException(
                        "nike-ph watcher needs a Nike product page URL (nike.com with /t/ in path), got: " + normUrl
                                + ". Example: https://www.nike.com/ph/t/air-jordan-11-retro-gamma-shoes-gMjfzz");
            }
        }

        List<Watcher> existing = list();
        String normLabel = nullToEmpty(w.label).toLowerCase();
        Instant now = Instant.now();

        // Dedup #1: same adapter + normalized URL + target already exists → refuse.
        for (Watcher e : existing) {
            if (w.adapter.equals(e.adapter)
                    && normalizeUrl(e.url).equals(normUrl)
                    && Objects.equals(nullToEmpty(e.target), nullToEmpty(w.target))) {
                throw new IllegalArgumentException(
                        "A watcher for this URL+target already exists: " + e.id
                                + " (" + e.label + "). Delete it first if you want to change settings.");
            }
        }

        // Dedup #2: same label + same target created within the last 10 seconds → likely an agent
        // double-call with two different guessed URLs. Refuse the second.
        if (!normLabel.isEmpty()) {
            for (Watcher e : existing) {
                if (!nullToEmpty(e.label).toLowerCase().equals(normLabel)) continue;
                if (!Objects.equals(nullToEmpty(e.target), nullToEmpty(w.target))) continue;
                try {
                    Instant created = Instant.parse(e.createdAt);
                    if (now.minusSeconds(10).isBefore(created)) {
                        throw new IllegalArgumentException(
                                "A watcher with this label was just created " + e.id
                                        + " — skipping duplicate. If the first one has a wrong URL, delete it.");
                    }
                } catch (java.time.format.DateTimeParseException ignored) {}
            }
        }

        if (w.intervalSeconds < properties.getMinIntervalSeconds()) {
            w.intervalSeconds = properties.getMinIntervalSeconds();
        }
        w.id = Long.toString(System.currentTimeMillis()) + "-" + Integer.toHexString(new Random().nextInt(0xFFFF));
        w.createdAt = Instant.now().toString();
        w.lastStatus = "unknown";
        write(w);
        log.info("[Watcher] Created {} ({}) every {}s", w.id, w.label, w.intervalSeconds);
        return w;
    }

    private static String normalizeUrl(String url) {
        if (url == null) return "";
        String u = url.trim();
        // Strip trailing slash for dedup comparison.
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s.trim(); }

    public void delete(String id) throws IOException {
        Path p = path(id);
        if (!Files.exists(p)) throw new IllegalArgumentException("Watcher not found: " + id);
        Files.delete(p);
    }

    public List<Watcher> list() throws IOException {
        Path dir = dir();
        if (!Files.isDirectory(dir)) return List.of();
        List<Watcher> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                Watcher w = read(p);
                if (w != null) out.add(w);
            }
        }
        out.sort(Comparator.comparing(a -> a.createdAt));
        return out;
    }

    /** Run a single check immediately (for test / manual trigger). */
    public Watcher triggerNow(String id) throws IOException {
        Path p = path(id);
        if (!Files.exists(p)) throw new IllegalArgumentException("Watcher not found: " + id);
        Watcher w = read(p);
        if (w == null) throw new IllegalArgumentException("Could not read watcher " + id);
        runCheck(w, true);
        return w;
    }

    private void tickLoop() {
        long tickMs = Math.max(1, properties.getTickIntervalSeconds()) * 1000L;
        while (running) {
            try {
                checkDue();
            } catch (Exception e) {
                log.warn("[Watcher] Tick error: {}", e.getMessage());
            }
            try { Thread.sleep(tickMs); } catch (InterruptedException e) { return; }
        }
    }

    private void checkDue() throws IOException {
        Path dir = dir();
        if (!Files.isDirectory(dir)) return;
        Instant now = Instant.now();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                Watcher w = read(p);
                if (w == null) continue;
                if (!isDue(w, now)) continue;
                runCheck(w, false);
            }
        }
    }

    private boolean isDue(Watcher w, Instant now) {
        if (w.lastCheckedAt == null || w.lastCheckedAt.isBlank()) return true;
        try {
            Instant last = Instant.parse(w.lastCheckedAt);
            return !now.isBefore(last.plusSeconds(w.intervalSeconds));
        } catch (Exception e) {
            return true;
        }
    }

    private void runCheck(Watcher w, boolean force) {
        WatcherAdapter adapter = adapters.get(w.adapter);
        if (adapter == null) {
            log.warn("[Watcher] Unknown adapter {} for watcher {}", w.adapter, w.id);
            return;
        }
        String previous = w.lastStatus;
        WatcherAdapter.CheckResult result;
        try {
            result = adapter.check(w);
        } catch (Exception e) {
            result = WatcherAdapter.CheckResult.error(e.getMessage());
        }
        w.lastStatus = result.status();
        w.lastCheckedAt = Instant.now().toString();
        try { write(w); } catch (IOException ioe) {
            log.warn("[Watcher] Failed to persist {}: {}", w.id, ioe.getMessage());
        }

        log.info("[Watcher] {} [{}] {} -> {} ({})",
                w.id, w.label, previous, result.status(), result.detail());

        boolean flippedToInStock = "in-stock".equals(result.status()) && !"in-stock".equals(previous);
        if (flippedToInStock || (force && "in-stock".equals(result.status()))) {
            notifier.notifyInStock(w, result.detail());
            w.lastNotifiedAt = Instant.now().toString();
            try { write(w); } catch (IOException ignored) {}
        }
    }

    private Watcher read(Path p) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = mapper.readValue(Files.readString(p), Map.class);
            return Watcher.fromMap(m);
        } catch (Exception e) {
            log.warn("[Watcher] Could not read {}: {}", p, e.getMessage());
            return null;
        }
    }

    private void write(Watcher w) throws IOException {
        Files.createDirectories(dir());
        Files.writeString(path(w.id),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(w.toMap()));
    }

    private Path path(String id) {
        if (id == null || !id.matches("[a-zA-Z0-9._-]+")) throw new IllegalArgumentException("Invalid id");
        return dir().resolve(id + ".json");
    }

    private Path dir() {
        return Paths.get(properties.getStorageDir()).toAbsolutePath().normalize();
    }

    public Set<String> adapterNames() { return adapters.keySet(); }
}
