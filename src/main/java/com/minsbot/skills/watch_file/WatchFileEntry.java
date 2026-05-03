package com.minsbot.skills.watch_file;

import java.util.LinkedHashMap;
import java.util.Map;

/** A single file watcher. Persisted as JSON in memory/watch_files/{id}.json. */
public class WatchFileEntry {
    public String id;
    public String label;
    public String path;
    /** "mtime" — fire on modified-time change. "hash" — fire when content hash flips.
     *  "regex" — fire when content matches the regex pattern. */
    public String mode = "mtime";
    /** Regex pattern when mode=regex. */
    public String pattern;
    public String notifyEmail;
    public String notifyWebhook;
    public int intervalSeconds = 60;

    /** Snapshot of the last seen state — mtime millis as String, or hex hash, or "matched"/"unmatched". */
    public String lastState = "";
    public String lastCheckedAt = "";
    public String lastNotifiedAt = "";
    public String createdAt = "";

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("label", label); m.put("path", path);
        m.put("mode", mode); m.put("pattern", pattern);
        m.put("notifyEmail", notifyEmail); m.put("notifyWebhook", notifyWebhook);
        m.put("intervalSeconds", intervalSeconds);
        m.put("lastState", lastState);
        m.put("lastCheckedAt", lastCheckedAt);
        m.put("lastNotifiedAt", lastNotifiedAt);
        m.put("createdAt", createdAt);
        return m;
    }

    public static WatchFileEntry fromMap(Map<String, Object> m) {
        WatchFileEntry w = new WatchFileEntry();
        w.id = (String) m.get("id");
        w.label = (String) m.get("label");
        w.path = (String) m.get("path");
        w.mode = (String) m.getOrDefault("mode", "mtime");
        w.pattern = (String) m.get("pattern");
        w.notifyEmail = (String) m.get("notifyEmail");
        w.notifyWebhook = (String) m.get("notifyWebhook");
        Object iv = m.get("intervalSeconds");
        w.intervalSeconds = iv instanceof Number n ? n.intValue() : 60;
        w.lastState = (String) m.getOrDefault("lastState", "");
        w.lastCheckedAt = (String) m.getOrDefault("lastCheckedAt", "");
        w.lastNotifiedAt = (String) m.getOrDefault("lastNotifiedAt", "");
        w.createdAt = (String) m.getOrDefault("createdAt", "");
        return w;
    }
}
