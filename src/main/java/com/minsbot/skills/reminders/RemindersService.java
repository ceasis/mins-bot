package com.minsbot.skills.reminders;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

@Service
public class RemindersService {

    private final RemindersConfig.RemindersProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();
    private Thread poller;
    private volatile boolean running;

    private final List<FiredListener> listeners = new CopyOnWriteArrayList<>();

    public interface FiredListener {
        void onFired(Map<String, Object> reminder);
    }

    public RemindersService(RemindersConfig.RemindersProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void start() {
        if (!properties.isEnabled()) return;
        running = true;
        poller = new Thread(this::pollLoop, "reminders-poller");
        poller.setDaemon(true);
        poller.start();
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (poller != null) poller.interrupt();
    }

    public void addListener(FiredListener listener) {
        listeners.add(listener);
    }

    public Map<String, Object> create(String message, String fireAtIso) throws IOException {
        if (message == null || message.isBlank()) throw new IllegalArgumentException("message required");
        Instant fireAt;
        try {
            fireAt = Instant.parse(fireAtIso);
        } catch (Exception e) {
            throw new IllegalArgumentException("fireAt must be ISO-8601, e.g. 2026-04-17T15:30:00Z");
        }
        String id = Long.toString(System.currentTimeMillis()) + "-" + Integer.toHexString(new Random().nextInt(0xFFFF));
        Map<String, Object> reminder = new LinkedHashMap<>();
        reminder.put("id", id);
        reminder.put("message", message);
        reminder.put("fireAt", fireAt.toString());
        reminder.put("createdAt", Instant.now().toString());
        reminder.put("fired", false);
        write(id, reminder);
        return reminder;
    }

    public void delete(String id) throws IOException {
        Path p = path(id);
        if (!Files.exists(p)) throw new IllegalArgumentException("Reminder not found: " + id);
        Files.delete(p);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> list(boolean includeFired) throws IOException {
        Path dir = dir();
        if (!Files.isDirectory(dir)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                try {
                    Map<String, Object> r = mapper.readValue(Files.readString(p), Map.class);
                    if (includeFired || !Boolean.TRUE.equals(r.get("fired"))) out.add(r);
                } catch (Exception ignored) {}
            }
        }
        out.sort(Comparator.comparing(a -> String.valueOf(a.get("fireAt"))));
        return out;
    }

    private void pollLoop() {
        long intervalMs = Math.max(1, properties.getPollIntervalSeconds()) * 1000L;
        while (running) {
            try {
                checkFires();
            } catch (Exception ignored) {}
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void checkFires() throws IOException {
        Path dir = dir();
        if (!Files.isDirectory(dir)) return;
        Instant now = Instant.now();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                try {
                    Map<String, Object> r = mapper.readValue(Files.readString(p), Map.class);
                    if (Boolean.TRUE.equals(r.get("fired"))) continue;
                    Instant fireAt = Instant.parse((String) r.get("fireAt"));
                    if (!now.isBefore(fireAt)) {
                        r.put("fired", true);
                        r.put("firedAt", now.toString());
                        Files.writeString(p, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(r));
                        for (FiredListener l : listeners) {
                            try { l.onFired(r); } catch (Exception ignored) {}
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    private void write(String id, Map<String, Object> r) throws IOException {
        Path dir = dir();
        Files.createDirectories(dir);
        Files.writeString(path(id), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(r));
    }

    private Path path(String id) {
        if (!id.matches("[a-zA-Z0-9._-]+")) throw new IllegalArgumentException("Invalid id");
        return dir().resolve(id + ".json");
    }

    private Path dir() {
        return Paths.get(properties.getStorageDir()).toAbsolutePath().normalize();
    }
}
