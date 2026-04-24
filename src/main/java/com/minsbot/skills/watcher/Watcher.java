package com.minsbot.skills.watcher;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single watcher record. Persisted as JSON in memory/watchers/{id}.json.
 *
 * adapter: "nike-ph" | "generic-http"
 * target:  adapter-specific key (e.g. shoe size "9.5" for nike-ph, regex for generic-http)
 * lastStatus: last known availability string ("in-stock" | "out-of-stock" | "unknown" | "error:<msg>")
 */
public class Watcher {
    public String id;
    public String label;
    public String url;
    public String adapter;
    public String target;
    public String notifyEmail;
    /** Optional webhook URL for instant push (Discord webhook, Pushover, ntfy.sh, or any HTTP endpoint). */
    public String notifyWebhook;
    public int intervalSeconds;
    /** Price ceiling. 0 = ignore price (stock-only). When > 0, watcher only fires if detected price ≤ maxPrice. */
    public double maxPrice;

    public String lastStatus = "unknown";
    public String lastCheckedAt = "";
    public String lastNotifiedAt = "";
    public String createdAt = "";

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("label", label);
        m.put("url", url);
        m.put("adapter", adapter);
        m.put("target", target);
        m.put("notifyEmail", notifyEmail);
        m.put("notifyWebhook", notifyWebhook);
        m.put("intervalSeconds", intervalSeconds);
        m.put("maxPrice", maxPrice);
        m.put("lastStatus", lastStatus);
        m.put("lastCheckedAt", lastCheckedAt);
        m.put("lastNotifiedAt", lastNotifiedAt);
        m.put("createdAt", createdAt);
        return m;
    }

    public static Watcher fromMap(Map<String, Object> m) {
        Watcher w = new Watcher();
        w.id = (String) m.get("id");
        w.label = (String) m.get("label");
        w.url = (String) m.get("url");
        w.adapter = (String) m.get("adapter");
        w.target = (String) m.get("target");
        w.notifyEmail = (String) m.get("notifyEmail");
        w.notifyWebhook = (String) m.get("notifyWebhook");
        Object interval = m.get("intervalSeconds");
        w.intervalSeconds = interval instanceof Number ? ((Number) interval).intValue() : 900;
        Object mp = m.get("maxPrice");
        w.maxPrice = mp instanceof Number ? ((Number) mp).doubleValue() : 0.0;
        w.lastStatus = (String) m.getOrDefault("lastStatus", "unknown");
        w.lastCheckedAt = (String) m.getOrDefault("lastCheckedAt", "");
        w.lastNotifiedAt = (String) m.getOrDefault("lastNotifiedAt", "");
        w.createdAt = (String) m.getOrDefault("createdAt", "");
        return w;
    }
}
