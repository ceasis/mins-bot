package com.minsbot.skills.watch_clipboard;

import java.util.LinkedHashMap;
import java.util.Map;

/** Clipboard watcher record. Persisted as JSON in memory/watch_clipboard/{id}.json. */
public class WatchClipboardEntry {
    public String id;
    public String label;
    /** Regex pattern. Empty = fire on ANY clipboard change. Non-empty = fire only when content matches. */
    public String pattern;
    public boolean caseInsensitive = true;
    public String notifyEmail;
    public String notifyWebhook;
    /** How often the FX clipboard is polled (seconds). Min 2. */
    public int intervalSeconds = 5;

    public String lastMatchedAt = "";
    public String lastSnippet = "";
    public String lastNotifiedAt = "";
    public String createdAt = "";

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("label", label);
        m.put("pattern", pattern);
        m.put("caseInsensitive", caseInsensitive);
        m.put("notifyEmail", notifyEmail); m.put("notifyWebhook", notifyWebhook);
        m.put("intervalSeconds", intervalSeconds);
        m.put("lastMatchedAt", lastMatchedAt);
        m.put("lastSnippet", lastSnippet);
        m.put("lastNotifiedAt", lastNotifiedAt);
        m.put("createdAt", createdAt);
        return m;
    }

    public static WatchClipboardEntry fromMap(Map<String, Object> m) {
        WatchClipboardEntry w = new WatchClipboardEntry();
        w.id = (String) m.get("id");
        w.label = (String) m.get("label");
        w.pattern = (String) m.get("pattern");
        Object ci = m.get("caseInsensitive");
        w.caseInsensitive = ci == null || (ci instanceof Boolean b ? b : !"false".equalsIgnoreCase(String.valueOf(ci)));
        w.notifyEmail = (String) m.get("notifyEmail");
        w.notifyWebhook = (String) m.get("notifyWebhook");
        Object iv = m.get("intervalSeconds");
        w.intervalSeconds = iv instanceof Number n ? n.intValue() : 5;
        w.lastMatchedAt = (String) m.getOrDefault("lastMatchedAt", "");
        w.lastSnippet = (String) m.getOrDefault("lastSnippet", "");
        w.lastNotifiedAt = (String) m.getOrDefault("lastNotifiedAt", "");
        w.createdAt = (String) m.getOrDefault("createdAt", "");
        return w;
    }
}
