package com.minsbot.skills.watch_http;

import java.util.LinkedHashMap;
import java.util.Map;

/** HTTP-status watcher record. Persisted as JSON in memory/watch_http/{id}.json. */
public class WatchHttpEntry {
    public String id;
    public String label;
    public String url;
    /** Optional: alert only when latency exceeds this many milliseconds. 0 = ignore. */
    public int slowAboveMs = 0;
    /** Whether to follow redirects (default true). */
    public boolean followRedirects = true;
    public String notifyEmail;
    public String notifyWebhook;
    public int intervalSeconds = 300;

    /** "up" / "down" / "" (unknown). Edge-triggered alerts on flip. */
    public String lastState = "";
    public int lastStatusCode;
    public long lastLatencyMs;
    public String lastCheckedAt = "";
    public String lastNotifiedAt = "";
    public String createdAt = "";

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("label", label); m.put("url", url);
        m.put("slowAboveMs", slowAboveMs);
        m.put("followRedirects", followRedirects);
        m.put("notifyEmail", notifyEmail); m.put("notifyWebhook", notifyWebhook);
        m.put("intervalSeconds", intervalSeconds);
        m.put("lastState", lastState);
        m.put("lastStatusCode", lastStatusCode);
        m.put("lastLatencyMs", lastLatencyMs);
        m.put("lastCheckedAt", lastCheckedAt);
        m.put("lastNotifiedAt", lastNotifiedAt);
        m.put("createdAt", createdAt);
        return m;
    }

    public static WatchHttpEntry fromMap(Map<String, Object> m) {
        WatchHttpEntry w = new WatchHttpEntry();
        w.id = (String) m.get("id");
        w.label = (String) m.get("label");
        w.url = (String) m.get("url");
        Object s = m.get("slowAboveMs");
        w.slowAboveMs = s instanceof Number n ? n.intValue() : 0;
        Object fr = m.get("followRedirects");
        w.followRedirects = fr == null || (fr instanceof Boolean b ? b : !"false".equalsIgnoreCase(String.valueOf(fr)));
        w.notifyEmail = (String) m.get("notifyEmail");
        w.notifyWebhook = (String) m.get("notifyWebhook");
        Object iv = m.get("intervalSeconds");
        w.intervalSeconds = iv instanceof Number n ? n.intValue() : 300;
        w.lastState = (String) m.getOrDefault("lastState", "");
        Object lc = m.get("lastStatusCode"); w.lastStatusCode = lc instanceof Number n ? n.intValue() : 0;
        Object ll = m.get("lastLatencyMs"); w.lastLatencyMs = ll instanceof Number n ? n.longValue() : 0;
        w.lastCheckedAt = (String) m.getOrDefault("lastCheckedAt", "");
        w.lastNotifiedAt = (String) m.getOrDefault("lastNotifiedAt", "");
        w.createdAt = (String) m.getOrDefault("createdAt", "");
        return w;
    }
}
