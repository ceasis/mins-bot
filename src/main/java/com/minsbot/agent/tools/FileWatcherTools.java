package com.minsbot.agent.tools;

import com.minsbot.agent.AsyncMessageService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Watch folders for new or modified files and push a chat notification when something changes.
 */
@Component
public class FileWatcherTools {

    private static final Logger log = LoggerFactory.getLogger(FileWatcherTools.class);

    private record WatchEntry(String id, String path, String label, WatchService ws, Thread thread, LocalDateTime created) {}

    private final ConcurrentHashMap<String, WatchEntry> watchers = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger(1);
    private final AsyncMessageService asyncMessages;

    public FileWatcherTools(AsyncMessageService asyncMessages) {
        this.asyncMessages = asyncMessages;
    }

    @Tool(description = "Watch a folder for new or modified files. The bot will send a chat notification whenever a file appears or changes. Returns a watcher ID you can use to stop it later.")
    public String watchFolder(
            @ToolParam(description = "Absolute path of the folder to watch") String folderPath,
            @ToolParam(description = "Short label describing this watcher, e.g. 'Downloads', 'Invoice inbox'") String label) {
        Path dir = Path.of(folderPath);
        if (!Files.isDirectory(dir)) return "Not a directory: " + folderPath;

        try {
            WatchService ws = FileSystems.getDefault().newWatchService();
            dir.register(ws,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY);

            String id = "fw" + counter.getAndIncrement();
            Thread t = new Thread(() -> loop(id, ws, dir, label), "mins-fw-" + id);
            t.setDaemon(true);
            t.start();

            watchers.put(id, new WatchEntry(id, folderPath, label, ws, t, LocalDateTime.now()));
            log.info("[FileWatcher:{}] Watching {}", id, folderPath);
            return "Watcher started (ID: " + id + ") on: " + folderPath;
        } catch (IOException e) {
            return "Failed to start watcher: " + e.getMessage();
        }
    }

    @Tool(description = "List all active folder watchers with their IDs and paths.")
    public String listWatchers() {
        if (watchers.isEmpty()) return "No active folder watchers.";
        StringBuilder sb = new StringBuilder("Active watchers:\n");
        String fmt = "HH:mm dd/MM";
        watchers.values().forEach(w ->
                sb.append("• [").append(w.id()).append("] ")
                  .append(w.label()).append(" → ").append(w.path())
                  .append(" (since ").append(w.created().format(DateTimeFormatter.ofPattern(fmt))).append(")\n"));
        return sb.toString().trim();
    }

    @Tool(description = "Stop a folder watcher by its ID.")
    public String removeWatcher(
            @ToolParam(description = "Watcher ID returned by watchFolder or listed by listWatchers") String id) {
        WatchEntry w = watchers.remove(id);
        if (w == null) return "No watcher found with ID: " + id;
        try { w.ws().close(); } catch (IOException ignored) {}
        w.thread().interrupt();
        return "Watcher " + id + " (" + w.label() + ") stopped.";
    }

    @Tool(description = "Stop all active folder watchers at once.")
    public String removeAllWatchers() {
        if (watchers.isEmpty()) return "No active watchers.";
        int count = watchers.size();
        watchers.keySet().forEach(id -> {
            WatchEntry w = watchers.remove(id);
            if (w != null) {
                try { w.ws().close(); } catch (IOException ignored) {}
                w.thread().interrupt();
            }
        });
        return "Stopped " + count + " watcher(s).";
    }

    private void loop(String id, WatchService ws, Path dir, String label) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                WatchKey key = ws.take();
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                    @SuppressWarnings("unchecked")
                    Path changed = dir.resolve(((WatchEvent<Path>) event).context());
                    String kind = event.kind() == StandardWatchEventKinds.ENTRY_CREATE ? "New file" : "Modified";
                    String msg = "\uD83D\uDCC1 **" + label + "** — " + kind + ": `" + changed.getFileName() + "`";
                    log.info("[FileWatcher:{}] {}", id, msg);
                    asyncMessages.push(msg);
                }
                if (!key.reset()) break;
            }
        } catch (InterruptedException | ClosedWatchServiceException e) {
            Thread.currentThread().interrupt();
        }
        log.info("[FileWatcher:{}] Stopped", id);
    }

    @PreDestroy
    public void shutdown() {
        watchers.values().forEach(w -> {
            try { w.ws().close(); } catch (IOException ignored) {}
        });
    }
}
