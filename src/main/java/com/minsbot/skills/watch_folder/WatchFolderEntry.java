package com.minsbot.skills.watch_folder;

import java.util.LinkedHashMap;
import java.util.Map;

/** A single folder watcher. Persisted as JSON in memory/watch_folders/{id}.json. */
public class WatchFolderEntry {
    public String id;
    public String label;
    public String path;
    /** "any" — fire on any create/modify/delete. "create" — only new files.
     *  "delete" — only deletions. "modify" — only modifications. */
    public String mode = "any";
    /** Optional glob filter on filename (e.g. "*.pdf", "report-*.csv"). null = no filter. */
    public String filter;
    public boolean recursive = false;
    public String notifyEmail;
    public String notifyWebhook;

    public String lastEvent = "";
    public String lastCheckedAt = "";
    public String lastNotifiedAt = "";
    public String createdAt = "";

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("label", label); m.put("path", path);
        m.put("mode", mode); m.put("filter", filter); m.put("recursive", recursive);
        m.put("notifyEmail", notifyEmail); m.put("notifyWebhook", notifyWebhook);
        m.put("lastEvent", lastEvent);
        m.put("lastCheckedAt", lastCheckedAt);
        m.put("lastNotifiedAt", lastNotifiedAt);
        m.put("createdAt", createdAt);
        return m;
    }

    public static WatchFolderEntry fromMap(Map<String, Object> m) {
        WatchFolderEntry w = new WatchFolderEntry();
        w.id = (String) m.get("id");
        w.label = (String) m.get("label");
        w.path = (String) m.get("path");
        w.mode = (String) m.getOrDefault("mode", "any");
        w.filter = (String) m.get("filter");
        Object r = m.get("recursive");
        w.recursive = r instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(r));
        w.notifyEmail = (String) m.get("notifyEmail");
        w.notifyWebhook = (String) m.get("notifyWebhook");
        w.lastEvent = (String) m.getOrDefault("lastEvent", "");
        w.lastCheckedAt = (String) m.getOrDefault("lastCheckedAt", "");
        w.lastNotifiedAt = (String) m.getOrDefault("lastNotifiedAt", "");
        w.createdAt = (String) m.getOrDefault("createdAt", "");
        return w;
    }
}
