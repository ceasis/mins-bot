package com.minsbot.agent.tools;

import com.minsbot.agent.AsyncMessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/**
 * Website monitoring tools — watch a URL for changes and alert the user.
 * Scrapes page content, saves a baseline, runs on a schedule, diffs against
 * baseline, sends desktop notification + speaks changes aloud.
 */
@Component
public class WebMonitorTools {

    private static final Logger log = LoggerFactory.getLogger(WebMonitorTools.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Path MONITOR_DIR = Paths.get(
            System.getProperty("user.home"), "mins_bot_data", "web_monitors");

    private final ToolExecutionNotifier notifier;
    private final AsyncMessageService asyncMessages;
    private final NotificationTools notificationTools;
    private final TtsTools ttsTools;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<String, MonitorEntry> activeMonitors = new ConcurrentHashMap<>();

    public WebMonitorTools(ToolExecutionNotifier notifier,
                           AsyncMessageService asyncMessages,
                           NotificationTools notificationTools,
                           TtsTools ttsTools) {
        this.notifier = notifier;
        this.asyncMessages = asyncMessages;
        this.notificationTools = notificationTools;
        this.ttsTools = ttsTools;
    }

    @Tool(description = "Start monitoring a website in the BACKGROUND for changes — runs forever, even when " +
            "you're not chatting. Takes a baseline snapshot now, then re-fetches at the given interval. If the " +
            "content diff is meaningful, sends a desktop notification and speaks a summary aloud. " +
            "USE THIS when the user wants: 'monitor this website', 'watch for changes on', 'alert me if X changes', " +
            "'check this URL every N minutes', 'notify me when X comes back in stock', 'alert me if product Y " +
            "becomes available', 'watch for a price drop on Z', 'tell me when Nike / PS5 / concert tickets restock', " +
            "'keep polling this URL'. DO NOT refuse such requests — this is the tool that does exactly that. " +
            "Interval is in minutes (minimum 1, maximum 1440 = 24h).")
    public String startMonitor(
            @ToolParam(description = "Full URL to monitor, e.g. 'https://status.openai.com'") String url,
            @ToolParam(description = "Check interval in minutes (e.g. 30 for every 30 minutes)") double intervalMinutes) {

        if (url == null || url.isBlank()) return "URL is required.";
        url = url.strip();
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url;

        int intervalMin = Math.max(1, Math.min(1440, (int) Math.round(intervalMinutes)));
        notifier.notify("Setting up website monitor for " + url + " (every " + intervalMin + " min)...");

        // Check if already monitoring this URL
        for (MonitorEntry e : activeMonitors.values()) {
            if (e.url.equals(url)) {
                return "Already monitoring " + url + " (ID: " + e.id + "). Use stopMonitor to stop it first.";
            }
        }

        // Take baseline snapshot
        String baseline;
        try {
            baseline = fetchPageContent(url);
            if (baseline == null || baseline.isBlank()) {
                return "Could not fetch content from " + url + ". Check the URL and try again.";
            }
        } catch (Exception e) {
            return "Failed to fetch " + url + ": " + e.getMessage();
        }

        // Save baseline to file
        String id = "mon-" + System.currentTimeMillis();
        try {
            Files.createDirectories(MONITOR_DIR);
            Path baselineFile = MONITOR_DIR.resolve(id + "_baseline.txt");
            Files.writeString(baselineFile, baseline, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "Failed to save baseline: " + e.getMessage();
        }

        // Schedule recurring check
        final String monitorUrl = url;
        final String monitorId = id;
        long intervalSec = intervalMin * 60L;

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            checkForChanges(monitorId, monitorUrl);
        }, intervalSec, intervalSec, TimeUnit.SECONDS);

        MonitorEntry entry = new MonitorEntry(id, url, baseline, intervalMin, future,
                LocalDateTime.now().format(FMT));
        activeMonitors.put(id, entry);

        log.info("[WebMonitor] Started: {} → {} (every {} min)", id, url, intervalMin);

        String intervalLabel = intervalMin >= 60
                ? (intervalMin / 60) + "h " + (intervalMin % 60) + "m"
                : intervalMin + " min";

        return "Website monitor started!\n"
                + "ID: " + id + "\n"
                + "URL: " + url + "\n"
                + "Interval: every " + intervalLabel + "\n"
                + "Baseline: " + baseline.length() + " chars captured\n"
                + "I'll alert you with a notification and speak any changes.";
    }

    @Tool(description = "Stop monitoring a website by its monitor ID. Use listMonitors to see active monitors.")
    public String stopMonitor(
            @ToolParam(description = "Monitor ID to stop (e.g. 'mon-1234567890')") String monitorId) {
        notifier.notify("Stopping monitor: " + monitorId);
        MonitorEntry entry = activeMonitors.remove(monitorId);
        if (entry == null) return "Monitor not found: " + monitorId;

        entry.future.cancel(false);

        // Clean up baseline file
        try {
            Files.deleteIfExists(MONITOR_DIR.resolve(monitorId + "_baseline.txt"));
        } catch (Exception ignored) {}

        log.info("[WebMonitor] Stopped: {} ({})", monitorId, entry.url);
        return "Stopped monitoring " + entry.url + " (ID: " + monitorId + ")";
    }

    @Tool(description = "List all active website monitors with their URLs, intervals, and status.")
    public String listMonitors() {
        notifier.notify("Listing website monitors...");
        if (activeMonitors.isEmpty()) return "No active website monitors.";

        StringBuilder sb = new StringBuilder("Active website monitors:\n\n");
        for (MonitorEntry entry : activeMonitors.values()) {
            sb.append("ID: ").append(entry.id).append("\n");
            sb.append("  URL: ").append(entry.url).append("\n");
            sb.append("  Interval: every ").append(entry.intervalMinutes).append(" min\n");
            sb.append("  Started: ").append(entry.startedAt).append("\n");
            sb.append("  Checks: ").append(entry.checkCount).append("\n");
            sb.append("  Changes detected: ").append(entry.changeCount).append("\n\n");
        }
        return sb.toString().trim();
    }

    @Tool(description = "Force an immediate check on a monitored website (don't wait for the next scheduled check). " +
            "Returns the current status and whether anything changed since baseline.")
    public String checkNow(
            @ToolParam(description = "Monitor ID to check now") String monitorId) {
        notifier.notify("Checking " + monitorId + " now...");
        MonitorEntry entry = activeMonitors.get(monitorId);
        if (entry == null) return "Monitor not found: " + monitorId;

        return checkForChanges(monitorId, entry.url);
    }

    // ─── Internal ────────────────────────────────────────────────────────

    private String checkForChanges(String monitorId, String url) {
        MonitorEntry entry = activeMonitors.get(monitorId);
        if (entry == null) return "Monitor no longer active.";

        entry.checkCount++;
        log.info("[WebMonitor] Check #{} for {} ({})", entry.checkCount, monitorId, url);

        try {
            String current = fetchPageContent(url);
            if (current == null || current.isBlank()) {
                log.warn("[WebMonitor] Empty response from {}", url);
                return "Could not fetch content (empty response).";
            }

            String previous = entry.lastContent;
            if (current.equals(previous)) {
                log.info("[WebMonitor] No change detected for {}", url);
                return "No changes detected on " + url + " (check #" + entry.checkCount + ")";
            }

            // Change detected — compute diff summary
            entry.changeCount++;
            String diffSummary = computeDiffSummary(previous, current, url);

            // Update baseline
            entry.lastContent = current;
            try {
                Files.writeString(MONITOR_DIR.resolve(monitorId + "_baseline.txt"),
                        current, StandardCharsets.UTF_8);
            } catch (Exception ignored) {}

            // Save change log
            try {
                Path logFile = MONITOR_DIR.resolve(monitorId + "_changes.log");
                String logEntry = "\n--- Change #" + entry.changeCount + " at "
                        + LocalDateTime.now().format(FMT) + " ---\n" + diffSummary + "\n";
                Files.writeString(logFile, logEntry, StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception ignored) {}

            // Notify user
            String alertTitle = "Website Changed: " + extractDomain(url);
            String alertBody = diffSummary.length() > 200
                    ? diffSummary.substring(0, 200) + "..."
                    : diffSummary;

            // Desktop notification
            try {
                notificationTools.showNotification(alertTitle, alertBody);
            } catch (Exception e) {
                log.warn("[WebMonitor] Notification failed: {}", e.getMessage());
            }

            // Speak it aloud
            try {
                String speakText = "Website change detected on " + extractDomain(url) + ". " + diffSummary;
                if (speakText.length() > 500) speakText = speakText.substring(0, 500);
                ttsTools.speak(speakText);
            } catch (Exception e) {
                log.warn("[WebMonitor] TTS failed: {}", e.getMessage());
            }

            // Push to chat
            asyncMessages.push("**Website change detected** on " + url + ":\n" + diffSummary);

            log.info("[WebMonitor] Change #{} on {}: {}", entry.changeCount, url,
                    diffSummary.substring(0, Math.min(100, diffSummary.length())));

            return "CHANGE DETECTED on " + url + " (change #" + entry.changeCount + "):\n" + diffSummary;

        } catch (Exception e) {
            log.warn("[WebMonitor] Check failed for {}: {}", url, e.getMessage());
            return "Check failed: " + e.getMessage();
        }
    }

    private String computeDiffSummary(String previous, String current, String url) {
        // Split into lines and find what changed
        String[] prevLines = previous.split("\n");
        String[] currLines = current.split("\n");

        Set<String> prevSet = new LinkedHashSet<>(Arrays.asList(prevLines));
        Set<String> currSet = new LinkedHashSet<>(Arrays.asList(currLines));

        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();

        for (String line : currLines) {
            if (!line.isBlank() && !prevSet.contains(line)) {
                added.add(line.trim());
            }
        }
        for (String line : prevLines) {
            if (!line.isBlank() && !currSet.contains(line)) {
                removed.add(line.trim());
            }
        }

        StringBuilder sb = new StringBuilder();

        if (!added.isEmpty()) {
            sb.append("New content:\n");
            for (String line : added) {
                if (sb.length() > 800) { sb.append("  ... and more\n"); break; }
                sb.append("  + ").append(line).append("\n");
            }
        }
        if (!removed.isEmpty()) {
            sb.append("Removed content:\n");
            for (String line : removed) {
                if (sb.length() > 800) { sb.append("  ... and more\n"); break; }
                sb.append("  - ").append(line).append("\n");
            }
        }

        if (sb.isEmpty()) {
            // Lines are same but order/whitespace changed
            sb.append("Content structure changed (").append(current.length())
              .append(" chars vs previous ").append(previous.length()).append(" chars)");
        }

        return sb.toString().trim();
    }

    private String fetchPageContent(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) MinsBotMonitor/1.0")
                .header("Accept", "text/html,application/xhtml+xml,*/*")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("HTTP " + response.statusCode());
        }

        String body = response.body();
        if (body == null) return "";

        // Strip HTML tags to get text content for comparison
        return body.replaceAll("<script[^>]*>[\\s\\S]*?</script>", "")
                   .replaceAll("<style[^>]*>[\\s\\S]*?</style>", "")
                   .replaceAll("<[^>]+>", " ")
                   .replaceAll("&nbsp;", " ")
                   .replaceAll("&amp;", "&")
                   .replaceAll("&lt;", "<")
                   .replaceAll("&gt;", ">")
                   .replaceAll("\\s+", " ")
                   .replaceAll("(?m)^\\s+$", "")
                   .replaceAll("\n{3,}", "\n\n")
                   .trim();
    }

    private String extractDomain(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    private static class MonitorEntry {
        final String id;
        final String url;
        String lastContent;
        final int intervalMinutes;
        final ScheduledFuture<?> future;
        final String startedAt;
        int checkCount = 0;
        int changeCount = 0;

        MonitorEntry(String id, String url, String initialContent, int intervalMinutes,
                     ScheduledFuture<?> future, String startedAt) {
            this.id = id;
            this.url = url;
            this.lastContent = initialContent;
            this.intervalMinutes = intervalMinutes;
            this.future = future;
            this.startedAt = startedAt;
        }
    }
}
