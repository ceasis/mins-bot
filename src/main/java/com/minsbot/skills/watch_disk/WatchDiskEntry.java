package com.minsbot.skills.watch_disk;

import java.util.LinkedHashMap;
import java.util.Map;

/** Disk-space watcher record. Persisted as JSON in memory/watch_disk/{id}.json. */
public class WatchDiskEntry {
    public String id;
    public String label;
    /** Drive root (e.g. "C:\\") or any path on the volume (we resolve to the file system root). */
    public String path;
    /** Threshold in GB. Watcher fires when free space drops below (or back above, on the next tick) this value. */
    public double freeBelowGb = 10.0;
    public String notifyEmail;
    public String notifyWebhook;
    public int intervalSeconds = 300;

    /** "ok" while free ≥ threshold, "low" while below. Used to fire only on the flip. */
    public String lastState = "";
    public double lastFreeGb;
    public double lastTotalGb;
    public String lastCheckedAt = "";
    public String lastNotifiedAt = "";
    public String createdAt = "";

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("label", label); m.put("path", path);
        m.put("freeBelowGb", freeBelowGb);
        m.put("notifyEmail", notifyEmail); m.put("notifyWebhook", notifyWebhook);
        m.put("intervalSeconds", intervalSeconds);
        m.put("lastState", lastState);
        m.put("lastFreeGb", lastFreeGb);
        m.put("lastTotalGb", lastTotalGb);
        m.put("lastCheckedAt", lastCheckedAt);
        m.put("lastNotifiedAt", lastNotifiedAt);
        m.put("createdAt", createdAt);
        return m;
    }

    public static WatchDiskEntry fromMap(Map<String, Object> m) {
        WatchDiskEntry w = new WatchDiskEntry();
        w.id = (String) m.get("id");
        w.label = (String) m.get("label");
        w.path = (String) m.get("path");
        Object f = m.get("freeBelowGb");
        w.freeBelowGb = f instanceof Number n ? n.doubleValue() : 10.0;
        w.notifyEmail = (String) m.get("notifyEmail");
        w.notifyWebhook = (String) m.get("notifyWebhook");
        Object iv = m.get("intervalSeconds");
        w.intervalSeconds = iv instanceof Number n ? n.intValue() : 300;
        w.lastState = (String) m.getOrDefault("lastState", "");
        Object lf = m.get("lastFreeGb"); w.lastFreeGb = lf instanceof Number n ? n.doubleValue() : 0;
        Object lt = m.get("lastTotalGb"); w.lastTotalGb = lt instanceof Number n ? n.doubleValue() : 0;
        w.lastCheckedAt = (String) m.getOrDefault("lastCheckedAt", "");
        w.lastNotifiedAt = (String) m.getOrDefault("lastNotifiedAt", "");
        w.createdAt = (String) m.getOrDefault("createdAt", "");
        return w;
    }
}
