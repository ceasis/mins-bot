package com.minsbot.skills.screenwatcher;

import com.minsbot.agent.AsyncMessageService;
import com.minsbot.agent.VisionService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Watches a rectangular region of the screen on a schedule. Each poll captures
 * the region, asks the vision model "does {condition} match?", and if the model
 * answers YES, posts an alert in chat and stops the watcher.
 *
 * <p>Examples the AI can set up:
 *  - "Tell me when this CI pipeline turns green" → region around the build status badge
 *  - "Ping me when this upload finishes" → region around a progress bar
 *  - "Alert me when any notification appears top-right" → region of the notification corner
 */
@Service
public class ScreenRegionWatcherService {

    private static final Logger log = LoggerFactory.getLogger(ScreenRegionWatcherService.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    @Autowired(required = false) private VisionService vision;
    @Autowired(required = false) private AsyncMessageService asyncMessages;

    private static final class Watcher {
        final String id;
        final Rectangle region;
        final String condition;
        final int intervalSec;
        final long startedAt;
        volatile ScheduledFuture<?> future;
        volatile int polls = 0;
        Watcher(String id, Rectangle r, String c, int i) {
            this.id = id; this.region = r; this.condition = c; this.intervalSec = i;
            this.startedAt = System.currentTimeMillis();
        }
    }

    private final ConcurrentHashMap<String, Watcher> watchers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "screen-watcher"); t.setDaemon(true); return t;
    });

    @Tool(description = "Watch a rectangular region of the screen and alert when a condition is met. "
            + "Captures the region every N seconds, asks the vision model if the condition matches, "
            + "and posts a chat alert + stops the watcher on match. "
            + "Use when the user says 'tell me when X appears', 'ping me when the upload finishes', "
            + "'alert me when CI turns green', 'notify me when this changes'. "
            + "Pass pixel coordinates of the region to watch (top-left x/y + width/height) and a clear "
            + "YES/NO condition in plain English. Returns a watcher ID.")
    public String watchScreenRegion(
            @ToolParam(description = "Top-left X coordinate of the region in pixels") int x,
            @ToolParam(description = "Top-left Y coordinate of the region in pixels") int y,
            @ToolParam(description = "Region width in pixels") int width,
            @ToolParam(description = "Region height in pixels") int height,
            @ToolParam(description = "Plain-English condition the vision model will answer YES/NO to, e.g. 'the progress bar is at 100%', 'the status badge is green', 'a toast notification is visible'") String condition,
            @ToolParam(description = "How often to poll in seconds (5-300)") Integer intervalSeconds) {
        if (vision == null) return "Vision service unavailable — cannot set up region watcher.";
        if (width <= 0 || height <= 0) return "Width and height must be positive.";
        if (condition == null || condition.isBlank()) return "Provide a clear YES/NO condition to watch for.";

        int interval = Math.max(5, Math.min(300, intervalSeconds != null ? intervalSeconds : 15));
        String id = "sw_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Rectangle r = new Rectangle(x, y, width, height);
        Watcher w = new Watcher(id, r, condition.trim(), interval);
        w.future = scheduler.scheduleAtFixedRate(() -> poll(w), interval, interval, TimeUnit.SECONDS);
        watchers.put(id, w);
        log.info("[ScreenWatcher:{}] Watching ({},{},{}x{}) every {}s — condition: {}",
                id, x, y, width, height, interval, condition);
        return "👁 Watching region (" + x + "," + y + "," + width + "×" + height + ") every "
                + interval + "s for: \"" + condition + "\". ID: " + id;
    }

    @Tool(description = "List all active screen-region watchers with their ID, region, condition, and uptime.")
    public String listScreenWatchers() {
        if (watchers.isEmpty()) return "No active screen watchers.";
        StringBuilder sb = new StringBuilder("Active screen watchers:\n");
        watchers.values().forEach(w -> {
            long secs = (System.currentTimeMillis() - w.startedAt) / 1000;
            sb.append("• [").append(w.id).append("] ")
              .append("(").append(w.region.x).append(",").append(w.region.y).append(",")
              .append(w.region.width).append("×").append(w.region.height).append(") ")
              .append("every ").append(w.intervalSec).append("s · polls: ").append(w.polls)
              .append(" · uptime: ").append(secs).append("s\n")
              .append("  condition: ").append(w.condition).append("\n");
        });
        return sb.toString().trim();
    }

    @Tool(description = "Stop a screen-region watcher by its ID.")
    public String stopScreenWatcher(
            @ToolParam(description = "Watcher ID returned by watchScreenRegion or shown in listScreenWatchers") String id) {
        Watcher w = watchers.remove(id);
        if (w == null) return "No watcher with ID: " + id;
        if (w.future != null) w.future.cancel(false);
        return "Stopped watcher " + id;
    }

    @Tool(description = "Stop all screen-region watchers at once.")
    public String stopAllScreenWatchers() {
        int n = watchers.size();
        if (n == 0) return "No active watchers.";
        List<String> ids = new ArrayList<>(watchers.keySet());
        for (String id : ids) stopScreenWatcher(id);
        return "Stopped " + n + " watcher(s).";
    }

    // ─── Internals ───

    private void poll(Watcher w) {
        w.polls++;
        try {
            BufferedImage shot = new Robot().createScreenCapture(w.region);
            Path tmp = Files.createTempFile("watch_" + w.id + "_", ".png");
            tmp.toFile().deleteOnExit();
            ImageIO.write(shot, "png", tmp.toFile());

            String prompt = "Look at this screenshot of a region of the user's screen. "
                    + "Answer with ONLY 'YES' or 'NO' on the first line, then one short sentence explaining what you see.\n\n"
                    + "Condition to check: " + w.condition;

            String answer = vision.analyzeWithPrompt(tmp, prompt);
            try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}

            if (answer == null) return;
            String firstLine = answer.strip().split("\\n", 2)[0].trim().toUpperCase();
            if (firstLine.startsWith("YES")) {
                // Condition matched — alert user and stop watching
                String ts = LocalDateTime.now().format(FMT);
                String msg = "👁 **Screen watcher fired** (" + ts + ")\n\n"
                        + "Condition met: _" + w.condition + "_\n\n"
                        + "Details: " + answer.strip();
                if (asyncMessages != null) asyncMessages.push(msg);
                log.info("[ScreenWatcher:{}] MATCH — stopping", w.id);
                stopScreenWatcher(w.id);
            }
        } catch (Exception e) {
            log.debug("[ScreenWatcher:{}] poll failed: {}", w.id, e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        watchers.values().forEach(w -> { if (w.future != null) w.future.cancel(false); });
        scheduler.shutdownNow();
    }
}
