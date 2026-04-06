package com.minsbot.agent.tools;

import com.minsbot.agent.AsyncMessageService;
import com.minsbot.agent.SystemControlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/**
 * System health monitoring tools — CPU/RAM/disk alerts and process watchdog.
 * Runs scheduled checks via PowerShell and notifies when thresholds are exceeded.
 */
@Component
public class HealthMonitorTools {

    private static final Logger log = LoggerFactory.getLogger(HealthMonitorTools.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ToolExecutionNotifier notifier;
    private final AsyncMessageService asyncMessages;
    private final NotificationTools notificationTools;
    private final TtsTools ttsTools;
    private final SystemControlService systemControl;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);
    private final Map<String, HealthEntry> activeMonitors = new ConcurrentHashMap<>();
    private final Map<String, WatchdogEntry> activeWatchdogs = new ConcurrentHashMap<>();

    public HealthMonitorTools(ToolExecutionNotifier notifier,
                              AsyncMessageService asyncMessages,
                              NotificationTools notificationTools,
                              TtsTools ttsTools,
                              SystemControlService systemControl) {
        this.notifier = notifier;
        this.asyncMessages = asyncMessages;
        this.notificationTools = notificationTools;
        this.ttsTools = ttsTools;
        this.systemControl = systemControl;
    }

    // ═══ System Snapshot ═══

    @Tool(description = "Get a full system health snapshot: CPU usage %, RAM used/total, disk space per drive, " +
            "and top 5 processes by memory. Use when the user asks about system health, PC performance, " +
            "how much RAM/CPU/disk is being used, or 'how's my system doing'.")
    public String getSystemHealth() {
        notifier.notify("Checking system health...");
        StringBuilder sb = new StringBuilder();

        // CPU
        String cpu = systemControl.runPowerShell(
                "(Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average");
        sb.append("CPU: ").append(cpu.trim()).append("% usage\n");

        // RAM
        String ram = systemControl.runPowerShell(
                "$os = Get-CimInstance Win32_OperatingSystem; " +
                "$totalGB = [math]::Round($os.TotalVisibleMemorySize/1MB, 1); " +
                "$freeGB = [math]::Round($os.FreePhysicalMemory/1MB, 1); " +
                "$usedGB = [math]::Round($totalGB - $freeGB, 1); " +
                "$pct = [math]::Round(($usedGB/$totalGB)*100, 0); " +
                "\"$usedGB GB / $totalGB GB ($pct%)\"");
        sb.append("RAM: ").append(ram.trim()).append("\n");

        // Disk
        String disk = systemControl.runPowerShell(
                "Get-CimInstance Win32_LogicalDisk -Filter 'DriveType=3' | ForEach-Object { " +
                "$totalGB = [math]::Round($_.Size/1GB, 1); " +
                "$freeGB = [math]::Round($_.FreeSpace/1GB, 1); " +
                "$usedGB = [math]::Round($totalGB - $freeGB, 1); " +
                "$pct = [math]::Round(($usedGB/$totalGB)*100, 0); " +
                "\"$($_.DeviceID) $usedGB/$totalGB GB ($pct% used, $freeGB GB free)\" }");
        sb.append("Disk:\n");
        for (String line : disk.trim().split("\n")) {
            if (!line.isBlank()) sb.append("  ").append(line.trim()).append("\n");
        }

        // Top processes by memory
        String procs = systemControl.runPowerShell(
                "Get-Process | Sort-Object WorkingSet64 -Descending | Select-Object -First 5 | " +
                "ForEach-Object { $mb = [math]::Round($_.WorkingSet64/1MB, 0); " +
                "\"$($_.ProcessName): $mb MB (CPU: $([math]::Round($_.CPU, 1))s)\" }");
        sb.append("Top processes (by RAM):\n");
        for (String line : procs.trim().split("\n")) {
            if (!line.isBlank()) sb.append("  ").append(line.trim()).append("\n");
        }

        return sb.toString().trim();
    }

    // ═══ Health Monitor ═══

    @Tool(description = "Start continuous system health monitoring with alerts. Checks CPU, RAM, and disk " +
            "at the specified interval. Sends a desktop notification and speaks aloud when any metric exceeds " +
            "its threshold. Use when the user says 'monitor my system', 'alert me if CPU goes above 90%', " +
            "'watch my disk space', etc.")
    public String startHealthMonitor(
            @ToolParam(description = "Check interval in minutes (1-60)") double intervalMinutes,
            @ToolParam(description = "CPU alert threshold percentage (e.g. 90)") double cpuThreshold,
            @ToolParam(description = "RAM alert threshold percentage (e.g. 85)") double ramThreshold,
            @ToolParam(description = "Disk alert threshold percentage (e.g. 90)") double diskThreshold) {

        int intervalMin = Math.max(1, Math.min(60, (int) Math.round(intervalMinutes)));
        int cpuLimit = Math.max(1, Math.min(100, (int) Math.round(cpuThreshold)));
        int ramLimit = Math.max(1, Math.min(100, (int) Math.round(ramThreshold)));
        int diskLimit = Math.max(1, Math.min(100, (int) Math.round(diskThreshold)));

        notifier.notify("Starting health monitor (every " + intervalMin + " min)...");

        // Only one health monitor at a time
        if (!activeMonitors.isEmpty()) {
            String existingId = activeMonitors.keySet().iterator().next();
            return "Health monitor already running (ID: " + existingId + "). Stop it first with stopHealthMonitor.";
        }

        String id = "health-" + System.currentTimeMillis();
        long intervalSec = intervalMin * 60L;

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            runHealthCheck(id, cpuLimit, ramLimit, diskLimit);
        }, intervalSec, intervalSec, TimeUnit.SECONDS);

        HealthEntry entry = new HealthEntry(id, intervalMin, cpuLimit, ramLimit, diskLimit,
                future, LocalDateTime.now().format(FMT));
        activeMonitors.put(id, entry);

        log.info("[HealthMonitor] Started: {} (every {} min, CPU>{}%, RAM>{}%, Disk>{}%)",
                id, intervalMin, cpuLimit, ramLimit, diskLimit);

        return "Health monitor started!\n"
                + "ID: " + id + "\n"
                + "Interval: every " + intervalMin + " min\n"
                + "Alerts when: CPU > " + cpuLimit + "%, RAM > " + ramLimit + "%, Disk > " + diskLimit + "%\n"
                + "I'll notify you and speak aloud when thresholds are exceeded.";
    }

    @Tool(description = "Stop the active health monitor.")
    public String stopHealthMonitor(
            @ToolParam(description = "Monitor ID (e.g. 'health-1234567890'), or 'all' to stop all") String monitorId) {
        notifier.notify("Stopping health monitor...");

        if ("all".equalsIgnoreCase(monitorId)) {
            int count = activeMonitors.size();
            activeMonitors.values().forEach(e -> e.future.cancel(false));
            activeMonitors.clear();
            return count > 0 ? "Stopped " + count + " health monitor(s)." : "No health monitors running.";
        }

        HealthEntry entry = activeMonitors.remove(monitorId);
        if (entry == null) return "Monitor not found: " + monitorId;
        entry.future.cancel(false);
        return "Stopped health monitor " + monitorId;
    }

    @Tool(description = "List active health monitors and process watchdogs with their status and alert counts.")
    public String listHealthMonitors() {
        notifier.notify("Listing health monitors...");
        StringBuilder sb = new StringBuilder();

        if (activeMonitors.isEmpty() && activeWatchdogs.isEmpty()) {
            return "No active health monitors or watchdogs.";
        }

        if (!activeMonitors.isEmpty()) {
            sb.append("Health Monitors:\n");
            for (HealthEntry e : activeMonitors.values()) {
                sb.append("  ").append(e.id).append("\n");
                sb.append("    Interval: every ").append(e.intervalMinutes).append(" min\n");
                sb.append("    Thresholds: CPU > ").append(e.cpuThreshold)
                  .append("%, RAM > ").append(e.ramThreshold)
                  .append("%, Disk > ").append(e.diskThreshold).append("%\n");
                sb.append("    Checks: ").append(e.checkCount)
                  .append(", Alerts: ").append(e.alertCount).append("\n");
                sb.append("    Started: ").append(e.startedAt).append("\n\n");
            }
        }

        if (!activeWatchdogs.isEmpty()) {
            sb.append("Process Watchdogs:\n");
            for (WatchdogEntry w : activeWatchdogs.values()) {
                sb.append("  ").append(w.id).append("\n");
                sb.append("    Process: ").append(w.processName).append("\n");
                sb.append("    Interval: every ").append(w.intervalMinutes).append(" min\n");
                sb.append("    Restarts: ").append(w.restartCount).append("\n");
                sb.append("    Started: ").append(w.startedAt).append("\n\n");
            }
        }

        return sb.toString().trim();
    }

    // ═══ Process Watchdog ═══

    @Tool(description = "Start a process watchdog that monitors if a specific process is running. " +
            "If the process dies/crashes, it automatically restarts it and alerts you. " +
            "Use when the user says 'watch this process', 'restart X if it crashes', " +
            "'keep this app running', 'watchdog for chrome', etc.")
    public String startWatchdog(
            @ToolParam(description = "Process name to watch (e.g. 'chrome', 'node', 'nginx', 'postgres')") String processName,
            @ToolParam(description = "Check interval in minutes (1-30)") double intervalMinutes,
            @ToolParam(description = "Command to restart the process (e.g. 'chrome', 'C:\\\\path\\\\to\\\\app.exe', or leave blank to use Start-Process)") String restartCommand) {

        if (processName == null || processName.isBlank()) return "Process name is required.";
        processName = processName.trim();
        int intervalMin = Math.max(1, Math.min(30, (int) Math.round(intervalMinutes)));

        notifier.notify("Starting watchdog for " + processName + "...");

        // Check for duplicate
        for (WatchdogEntry w : activeWatchdogs.values()) {
            if (w.processName.equalsIgnoreCase(processName)) {
                return "Already watching " + processName + " (ID: " + w.id + "). Stop it first.";
            }
        }

        String id = "watch-" + System.currentTimeMillis();
        String restart = (restartCommand == null || restartCommand.isBlank()) ? processName : restartCommand.trim();
        long intervalSec = intervalMin * 60L;
        final String procName = processName;
        final String restartCmd = restart;

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            checkProcess(id, procName, restartCmd);
        }, intervalSec, intervalSec, TimeUnit.SECONDS);

        WatchdogEntry entry = new WatchdogEntry(id, procName, restart, intervalMin,
                future, LocalDateTime.now().format(FMT));
        activeWatchdogs.put(id, entry);

        log.info("[Watchdog] Started: {} → {} (every {} min)", id, processName, intervalMin);

        return "Process watchdog started!\n"
                + "ID: " + id + "\n"
                + "Watching: " + processName + "\n"
                + "Interval: every " + intervalMin + " min\n"
                + "Restart command: " + restart + "\n"
                + "I'll restart it and alert you if it stops running.";
    }

    @Tool(description = "Stop a process watchdog by its ID.")
    public String stopWatchdog(
            @ToolParam(description = "Watchdog ID (e.g. 'watch-1234567890'), or 'all' to stop all") String watchdogId) {
        notifier.notify("Stopping watchdog...");

        if ("all".equalsIgnoreCase(watchdogId)) {
            int count = activeWatchdogs.size();
            activeWatchdogs.values().forEach(w -> w.future.cancel(false));
            activeWatchdogs.clear();
            return count > 0 ? "Stopped " + count + " watchdog(s)." : "No watchdogs running.";
        }

        WatchdogEntry entry = activeWatchdogs.remove(watchdogId);
        if (entry == null) return "Watchdog not found: " + watchdogId;
        entry.future.cancel(false);
        return "Stopped watchdog for " + entry.processName + " (ID: " + watchdogId + ")";
    }

    @Tool(description = "Get a list of all running processes with their CPU and memory usage. " +
            "Use when the user asks 'what processes are running', 'show me top processes', 'what's eating my RAM'.")
    public String getTopProcesses(
            @ToolParam(description = "Number of processes to show (1-30), sorted by memory usage") double count) {
        int n = Math.max(1, Math.min(30, (int) Math.round(count)));
        notifier.notify("Getting top " + n + " processes...");

        String result = systemControl.runPowerShell(
                "Get-Process | Where-Object { $_.WorkingSet64 -gt 10MB } | " +
                "Sort-Object WorkingSet64 -Descending | Select-Object -First " + n + " | " +
                "ForEach-Object { $mb = [math]::Round($_.WorkingSet64/1MB, 0); " +
                "$cpu = [math]::Round($_.CPU, 1); " +
                "\"$($_.ProcessName) | PID: $($_.Id) | RAM: $mb MB | CPU: ${cpu}s\" }");

        if (result == null || result.isBlank()) return "No processes found.";

        StringBuilder sb = new StringBuilder("Top " + n + " processes by memory:\n\n");
        int i = 0;
        for (String line : result.trim().split("\n")) {
            if (!line.isBlank()) {
                i++;
                sb.append(i).append(". ").append(line.trim()).append("\n");
            }
        }
        return sb.toString().trim();
    }

    @Tool(description = "Kill a process by its name or PID. Use when the user says 'kill chrome', " +
            "'stop this process', 'end task for X'. Be careful with system processes.")
    public String killProcess(
            @ToolParam(description = "Process name (e.g. 'chrome') or PID (e.g. '12345')") String nameOrPid) {
        if (nameOrPid == null || nameOrPid.isBlank()) return "Process name or PID required.";
        notifier.notify("Killing process: " + nameOrPid + "...");

        String target = nameOrPid.trim();
        String result;
        try {
            int pid = Integer.parseInt(target);
            result = systemControl.runPowerShell("Stop-Process -Id " + pid + " -Force -ErrorAction Stop; 'Killed PID " + pid + "'");
        } catch (NumberFormatException e) {
            result = systemControl.runPowerShell("Stop-Process -Name '" + target + "' -Force -ErrorAction Stop; 'Killed " + target + "'");
        }

        return result.trim();
    }

    // ─── Internal ────────────────────────────────────────────────────────

    private void runHealthCheck(String monitorId, int cpuLimit, int ramLimit, int diskLimit) {
        HealthEntry entry = activeMonitors.get(monitorId);
        if (entry == null) return;
        entry.checkCount++;

        log.debug("[HealthMonitor] Check #{} (CPU>{}%, RAM>{}%, Disk>{}%)",
                entry.checkCount, cpuLimit, ramLimit, diskLimit);

        List<String> alerts = new ArrayList<>();

        try {
            // CPU check
            String cpuRaw = systemControl.runPowerShell(
                    "(Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average").trim();
            try {
                int cpu = (int) Double.parseDouble(cpuRaw);
                if (cpu > cpuLimit) {
                    alerts.add("CPU at " + cpu + "% (threshold: " + cpuLimit + "%)");
                }
            } catch (NumberFormatException ignored) {}

            // RAM check
            String ramRaw = systemControl.runPowerShell(
                    "$os = Get-CimInstance Win32_OperatingSystem; " +
                    "[math]::Round((($os.TotalVisibleMemorySize - $os.FreePhysicalMemory) / $os.TotalVisibleMemorySize) * 100, 0)").trim();
            try {
                int ram = (int) Double.parseDouble(ramRaw);
                if (ram > ramLimit) {
                    alerts.add("RAM at " + ram + "% (threshold: " + ramLimit + "%)");
                }
            } catch (NumberFormatException ignored) {}

            // Disk check
            String diskRaw = systemControl.runPowerShell(
                    "Get-CimInstance Win32_LogicalDisk -Filter 'DriveType=3' | ForEach-Object { " +
                    "$pct = [math]::Round((($_.Size - $_.FreeSpace) / $_.Size) * 100, 0); " +
                    "\"$($_.DeviceID)|$pct\" }").trim();
            for (String line : diskRaw.split("\n")) {
                if (line.contains("|")) {
                    String[] parts = line.trim().split("\\|");
                    if (parts.length == 2) {
                        try {
                            int pct = (int) Double.parseDouble(parts[1]);
                            if (pct > diskLimit) {
                                alerts.add("Disk " + parts[0] + " at " + pct + "% (threshold: " + diskLimit + "%)");
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[HealthMonitor] Check failed: {}", e.getMessage());
        }

        if (!alerts.isEmpty()) {
            entry.alertCount++;
            String summary = String.join(", ", alerts);
            String title = "System Alert";
            String body = summary;

            // Desktop notification
            try { notificationTools.showNotification(title, body); } catch (Exception ignored) {}

            // Speak
            try { ttsTools.speak("System health alert: " + summary); } catch (Exception ignored) {}

            // Push to chat
            asyncMessages.push("**System Health Alert** (check #" + entry.checkCount + "):\n" + summary);

            log.warn("[HealthMonitor] ALERT #{}: {}", entry.alertCount, summary);
        }
    }

    private void checkProcess(String watchdogId, String processName, String restartCommand) {
        WatchdogEntry entry = activeWatchdogs.get(watchdogId);
        if (entry == null) return;

        log.debug("[Watchdog] Checking process: {}", processName);

        String check = systemControl.runPowerShell(
                "(Get-Process -Name '" + processName + "' -ErrorAction SilentlyContinue).Count").trim();

        int count = 0;
        try { count = Integer.parseInt(check); } catch (Exception ignored) {}

        if (count > 0) {
            log.debug("[Watchdog] {} is running ({} instances)", processName, count);
            return;
        }

        // Process is not running — restart it
        entry.restartCount++;
        log.warn("[Watchdog] {} is DOWN — restarting (attempt #{})", processName, entry.restartCount);

        String restartResult;
        try {
            restartResult = systemControl.runPowerShell(
                    "Start-Process '" + restartCommand + "' -ErrorAction Stop; 'Restarted'");
        } catch (Exception e) {
            restartResult = "Failed: " + e.getMessage();
        }

        String summary = processName + " was not running. Restart attempt #" + entry.restartCount
                + ": " + restartResult.trim();

        // Notify
        try { notificationTools.showNotification("Watchdog: " + processName, summary); } catch (Exception ignored) {}
        try { ttsTools.speak("Process watchdog alert: " + processName + " was down. I restarted it."); } catch (Exception ignored) {}
        asyncMessages.push("**Watchdog Alert**: " + summary);

        log.info("[Watchdog] Restart result: {}", restartResult.trim());
    }

    // ─── Data classes ────────────────────────────────────────────────────

    private static class HealthEntry {
        final String id;
        final int intervalMinutes;
        final int cpuThreshold;
        final int ramThreshold;
        final int diskThreshold;
        final ScheduledFuture<?> future;
        final String startedAt;
        int checkCount = 0;
        int alertCount = 0;

        HealthEntry(String id, int intervalMinutes, int cpuThreshold, int ramThreshold,
                    int diskThreshold, ScheduledFuture<?> future, String startedAt) {
            this.id = id;
            this.intervalMinutes = intervalMinutes;
            this.cpuThreshold = cpuThreshold;
            this.ramThreshold = ramThreshold;
            this.diskThreshold = diskThreshold;
            this.future = future;
            this.startedAt = startedAt;
        }
    }

    private static class WatchdogEntry {
        final String id;
        final String processName;
        final String restartCommand;
        final int intervalMinutes;
        final ScheduledFuture<?> future;
        final String startedAt;
        int restartCount = 0;

        WatchdogEntry(String id, String processName, String restartCommand, int intervalMinutes,
                      ScheduledFuture<?> future, String startedAt) {
            this.id = id;
            this.processName = processName;
            this.restartCommand = restartCommand;
            this.intervalMinutes = intervalMinutes;
            this.future = future;
            this.startedAt = startedAt;
        }
    }
}
