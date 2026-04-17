package com.minsbot.skills.clipboardhistory;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

@Service
public class ClipboardHistoryService {

    private final ClipboardHistoryConfig.ClipboardHistoryProperties properties;
    private final Deque<Entry> history = new ConcurrentLinkedDeque<>();
    private final Set<String> pinnedIds = Collections.synchronizedSet(new LinkedHashSet<>());
    private Thread poller;
    private volatile boolean running;
    private String lastSeen;

    public static class Entry {
        public String id;
        public String text;
        public String capturedAt;
    }

    public ClipboardHistoryService(ClipboardHistoryConfig.ClipboardHistoryProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void start() {
        if (!properties.isEnabled()) return;
        if (java.awt.GraphicsEnvironment.isHeadless()) return;
        running = true;
        poller = new Thread(this::pollLoop, "clipboard-history-poller");
        poller.setDaemon(true);
        poller.start();
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (poller != null) poller.interrupt();
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Entry e : history) out.add(toMap(e));
        return out;
    }

    public List<Map<String, Object>> search(String query) {
        String q = query == null ? "" : query.toLowerCase();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Entry e : history) {
            if (e.text.toLowerCase().contains(q)) out.add(toMap(e));
        }
        return out;
    }

    public void pin(String id) {
        if (history.stream().noneMatch(e -> e.id.equals(id))) {
            throw new IllegalArgumentException("Entry not found: " + id);
        }
        pinnedIds.add(id);
    }

    public void unpin(String id) {
        pinnedIds.remove(id);
    }

    public void clear() {
        List<Entry> keep = new ArrayList<>();
        for (Entry e : history) if (pinnedIds.contains(e.id)) keep.add(e);
        history.clear();
        history.addAll(keep);
    }

    private void pollLoop() {
        long interval = Math.max(250, properties.getPollIntervalMs());
        while (running) {
            try {
                Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
                if (cb.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                    String text = (String) cb.getData(DataFlavor.stringFlavor);
                    if (text != null && !text.isEmpty() && !text.equals(lastSeen)) {
                        if (text.length() > properties.getMaxEntryChars()) {
                            text = text.substring(0, properties.getMaxEntryChars());
                        }
                        addEntry(text);
                        lastSeen = text;
                    }
                }
            } catch (Exception ignored) {}
            try {
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void addEntry(String text) {
        Entry e = new Entry();
        e.id = Long.toString(System.currentTimeMillis()) + "-" + Integer.toHexString(new Random().nextInt(0xFFFF));
        e.text = text;
        e.capturedAt = Instant.now().toString();
        history.addFirst(e);

        while (history.size() > properties.getMaxEntries()) {
            Entry oldest = history.pollLast();
            if (oldest != null && pinnedIds.contains(oldest.id)) {
                history.addLast(oldest);
                break;
            }
        }
    }

    private Map<String, Object> toMap(Entry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id);
        m.put("text", e.text);
        m.put("capturedAt", e.capturedAt);
        m.put("pinned", pinnedIds.contains(e.id));
        return m;
    }
}
