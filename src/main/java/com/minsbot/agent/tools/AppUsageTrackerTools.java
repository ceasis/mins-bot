package com.minsbot.agent.tools;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks which app/window has focus and for how long, polled every 10 seconds.
 * Data lives in memory (resets on restart). Feeds into habit detection.
 */
@Component
public class AppUsageTrackerTools {

    private static final Logger log = LoggerFactory.getLogger(AppUsageTrackerTools.class);
    private static final int POLL_SECONDS = 10;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    /** day (YYYY-MM-DD) → appName → seconds */
    private final ConcurrentHashMap<String, Map<String, Long>> usage = new ConcurrentHashMap<>();
    private final AtomicBoolean enabled = new AtomicBoolean(true);

    @Scheduled(fixedDelay = POLL_SECONDS * 1000L)
    public void poll() {
        if (!enabled.get()) return;
        String app = getForegroundApp();
        if (app == null || app.isBlank()) return;
        String today = LocalDate.now().format(DATE_FMT);
        usage.computeIfAbsent(today, k -> new ConcurrentHashMap<>())
             .merge(app, (long) POLL_SECONDS, Long::sum);
    }

    @Tool(description = "Get the currently focused application or window title.")
    public String getCurrentFocusedApp() {
        String app = getForegroundApp();
        return app != null && !app.isBlank() ? "Currently focused: " + app : "Could not detect the active window.";
    }

    @Tool(description = "Get app usage summary for a specific date showing time spent in each app. Leave date blank for today.")
    public String getAppUsageSummary(
            @ToolParam(description = "Date as YYYY-MM-DD, or blank for today") String date) {
        String key = (date == null || date.isBlank()) ? LocalDate.now().format(DATE_FMT) : date.trim();
        Map<String, Long> day = usage.get(key);
        if (day == null || day.isEmpty()) return "No usage data recorded for " + key + ".";

        StringBuilder sb = new StringBuilder("App usage on ").append(key).append(":\n");
        day.entrySet().stream()
           .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
           .limit(20)
           .forEach(e -> sb.append("• ").append(e.getKey())
                   .append(" — ").append(fmt(e.getValue())).append("\n"));
        return sb.toString().trim();
    }

    @Tool(description = "Get the most-used apps ranked by total time across the last N days (default 7).")
    public String getMostUsedApps(
            @ToolParam(description = "Number of days to look back (default 7)") Integer days) {
        int d = days != null ? Math.max(1, Math.min(days, 90)) : 7;
        Map<String, Long> totals = new LinkedHashMap<>();
        LocalDate today = LocalDate.now();
        for (int i = 0; i < d; i++) {
            Map<String, Long> day = usage.get(today.minusDays(i).format(DATE_FMT));
            if (day != null) day.forEach((app, secs) -> totals.merge(app, secs, Long::sum));
        }
        if (totals.isEmpty()) return "No usage data for the last " + d + " days.";

        StringBuilder sb = new StringBuilder("Top apps (last ").append(d).append(" days):\n");
        totals.entrySet().stream()
              .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
              .limit(15)
              .forEach(e -> sb.append("• ").append(e.getKey())
                      .append(" — ").append(fmt(e.getValue())).append("\n"));
        return sb.toString().trim();
    }

    @Tool(description = "Get a breakdown of productive vs. leisure app time today (categorizes by common app names).")
    public String getProductivityBreakdown() {
        String key = LocalDate.now().format(DATE_FMT);
        Map<String, Long> day = usage.get(key);
        if (day == null || day.isEmpty()) return "No usage data for today yet.";

        Set<String> productive = Set.of("code", "intellij", "eclipse", "visual studio", "terminal",
                "cmd", "powershell", "word", "excel", "powerpoint", "notepad", "idea", "rider",
                "datagrip", "webstorm", "pycharm");
        Set<String> leisure = Set.of("youtube", "netflix", "spotify", "steam", "game",
                "discord", "facebook", "instagram", "twitter", "tiktok", "reddit");

        long prodSecs = 0, leisureSecs = 0, otherSecs = 0;
        for (Map.Entry<String, Long> e : day.entrySet()) {
            String name = e.getKey().toLowerCase();
            boolean isProd = productive.stream().anyMatch(name::contains);
            boolean isLeisure = leisure.stream().anyMatch(name::contains);
            if (isProd) prodSecs += e.getValue();
            else if (isLeisure) leisureSecs += e.getValue();
            else otherSecs += e.getValue();
        }
        long total = prodSecs + leisureSecs + otherSecs;
        return String.format("Productivity breakdown today:\n• Work/Dev: %s (%.0f%%)\n• Leisure: %s (%.0f%%)\n• Other: %s (%.0f%%)\nTotal tracked: %s",
                fmt(prodSecs), pct(prodSecs, total),
                fmt(leisureSecs), pct(leisureSecs, total),
                fmt(otherSecs), pct(otherSecs, total),
                fmt(total));
    }

    @Tool(description = "Enable or disable app usage tracking.")
    public String setAppTracking(
            @ToolParam(description = "true to enable tracking, false to pause it") boolean trackingEnabled) {
        enabled.set(trackingEnabled);
        return "App usage tracking " + (trackingEnabled ? "enabled" : "paused") + ".";
    }

    // ─── Internals ───

    private String getForegroundApp() {
        try {
            WinDef.HWND hwnd = User32.INSTANCE.GetForegroundWindow();
            if (hwnd == null) return null;
            char[] buf = new char[512];
            User32.INSTANCE.GetWindowText(hwnd, buf, buf.length);
            String title = Native.toString(buf).trim();
            if (title.isBlank()) return null;
            // "My Document - Microsoft Word" → "Microsoft Word"
            if (title.contains(" - ")) {
                String[] parts = title.split(" - ");
                return parts[parts.length - 1].trim();
            }
            return title;
        } catch (Exception e) {
            return null;
        }
    }

    private String fmt(long secs) {
        if (secs < 60) return secs + "s";
        long m = secs / 60;
        if (m < 60) return m + "m";
        return (m / 60) + "h " + (m % 60) + "m";
    }

    private double pct(long part, long total) {
        return total == 0 ? 0.0 : (part * 100.0 / total);
    }
}
