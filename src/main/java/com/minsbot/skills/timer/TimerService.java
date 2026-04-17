package com.minsbot.skills.timer;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TimerService {

    private final Map<String, TimerEntry> timers = new ConcurrentHashMap<>();

    public static class TimerEntry {
        public String id;
        public String name;
        public long durationMs;
        public long startedAtMs;
        public Long pausedAtMs;
        public long pausedElapsedMs;
        public boolean running;
    }

    public Map<String, Object> start(String name, long durationMs) {
        if (durationMs <= 0) throw new IllegalArgumentException("durationMs must be > 0");
        String id = Long.toString(System.currentTimeMillis()) + "-" + Integer.toHexString(new Random().nextInt(0xFFFF));
        TimerEntry t = new TimerEntry();
        t.id = id;
        t.name = name == null ? "" : name;
        t.durationMs = durationMs;
        t.startedAtMs = System.currentTimeMillis();
        t.running = true;
        timers.put(id, t);
        return snapshot(t);
    }

    public Map<String, Object> pause(String id) {
        TimerEntry t = get(id);
        if (!t.running) throw new IllegalArgumentException("Timer already paused");
        t.running = false;
        t.pausedAtMs = System.currentTimeMillis();
        t.pausedElapsedMs += t.pausedAtMs - t.startedAtMs;
        return snapshot(t);
    }

    public Map<String, Object> resume(String id) {
        TimerEntry t = get(id);
        if (t.running) throw new IllegalArgumentException("Timer already running");
        t.running = true;
        t.startedAtMs = System.currentTimeMillis();
        t.pausedAtMs = null;
        return snapshot(t);
    }

    public Map<String, Object> cancel(String id) {
        TimerEntry t = timers.remove(id);
        if (t == null) throw new IllegalArgumentException("Timer not found: " + id);
        return Map.of("cancelled", id);
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TimerEntry t : timers.values()) out.add(snapshot(t));
        out.sort(Comparator.comparing(a -> String.valueOf(a.get("id"))));
        return out;
    }

    public Map<String, Object> get(String id, boolean asSnapshot) {
        return snapshot(get(id));
    }

    public int size() { return timers.size(); }

    private TimerEntry get(String id) {
        TimerEntry t = timers.get(id);
        if (t == null) throw new IllegalArgumentException("Timer not found: " + id);
        return t;
    }

    private Map<String, Object> snapshot(TimerEntry t) {
        long now = System.currentTimeMillis();
        long elapsed = t.pausedElapsedMs + (t.running ? (now - t.startedAtMs) : 0);
        long remaining = Math.max(0, t.durationMs - elapsed);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", t.id);
        out.put("name", t.name);
        out.put("durationMs", t.durationMs);
        out.put("elapsedMs", elapsed);
        out.put("remainingMs", remaining);
        out.put("expired", remaining == 0);
        out.put("running", t.running);
        out.put("expiresAt", t.running ? Instant.ofEpochMilli(now + remaining).toString() : null);
        return out;
    }
}
