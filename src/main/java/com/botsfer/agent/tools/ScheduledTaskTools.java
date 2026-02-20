package com.botsfer.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/**
 * AI-callable tools for scheduling reminders and recurring tasks.
 * Tasks run in-memory; they do not survive a restart.
 */
@Component
public class ScheduledTaskTools {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskTools.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ToolExecutionNotifier notifier;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<String, ScheduledTaskEntry> tasks = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> firedReminders = new ConcurrentLinkedQueue<>();

    public ScheduledTaskTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @Tool(description = "Schedule a one-shot reminder that fires after a delay (tracked by ID; use listScheduledTasks/cancelScheduledTask). " +
            "Use when the user says 'remind me in 5 minutes to...' and you need list/cancel support.")
    public String scheduleReminder(
            @ToolParam(description = "Reminder message, e.g. 'Take a break'") String message,
            @ToolParam(description = "Delay in minutes before the reminder fires") int delayMinutes) {
        notifier.notify("Setting reminder: " + delayMinutes + "min");
        try {
            if (delayMinutes < 1) delayMinutes = 1;
            if (delayMinutes > 1440 * 7) delayMinutes = 1440 * 7; // max 7 days

            String id = "rem-" + System.currentTimeMillis();
            LocalDateTime fireTime = LocalDateTime.now().plusMinutes(delayMinutes);

            ScheduledFuture<?> future = scheduler.schedule(() -> {
                String notification = "REMINDER: " + message;
                firedReminders.add(notification);
                notifier.notify(notification);
                log.info("[Scheduler] Reminder fired: {}", message);
                tasks.get(id).status = "fired";
            }, delayMinutes, TimeUnit.MINUTES);

            tasks.put(id, new ScheduledTaskEntry(id, "reminder", message,
                    fireTime.format(FMT), null, "pending", future));

            log.info("[Scheduler] Reminder set: '{}' fires at {}", message, fireTime.format(FMT));
            return "Reminder set! ID: " + id + ". Will fire at " + fireTime.format(FMT)
                    + " (in " + delayMinutes + " minutes): " + message;
        } catch (Exception e) {
            return "Failed to set reminder: " + e.getMessage();
        }
    }

    @Tool(description = "Schedule a recurring task that repeats at a fixed interval. " +
            "Use for things like 'check this URL every 30 minutes' or 'remind me every hour'.")
    public String scheduleRecurring(
            @ToolParam(description = "Task description or message") String description,
            @ToolParam(description = "Interval in minutes between each execution") int intervalMinutes) {
        notifier.notify("Scheduling recurring: every " + intervalMinutes + "min");
        try {
            if (intervalMinutes < 1) intervalMinutes = 1;
            if (intervalMinutes > 1440) intervalMinutes = 1440; // max daily

            String id = "rec-" + System.currentTimeMillis();
            int interval = intervalMinutes;

            ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
                String notification = "RECURRING: " + description;
                firedReminders.add(notification);
                notifier.notify(notification);
                log.info("[Scheduler] Recurring task fired: {}", description);
            }, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);

            tasks.put(id, new ScheduledTaskEntry(id, "recurring", description,
                    LocalDateTime.now().plusMinutes(interval).format(FMT),
                    interval + " min", "active", future));

            log.info("[Scheduler] Recurring task: '{}' every {} min", description, interval);
            return "Recurring task scheduled! ID: " + id + ". Runs every "
                    + interval + " minutes: " + description;
        } catch (Exception e) {
            return "Failed to schedule recurring task: " + e.getMessage();
        }
    }

    @Tool(description = "Cancel a scheduled task or reminder by its ID.")
    public String cancelScheduledTask(
            @ToolParam(description = "The task ID to cancel (e.g. 'rem-1234567890' or 'rec-1234567890')") String taskId) {
        notifier.notify("Cancelling task: " + taskId);
        ScheduledTaskEntry entry = tasks.get(taskId);
        if (entry == null) return "No task found with ID: " + taskId;
        entry.future.cancel(false);
        entry.status = "cancelled";
        log.info("[Scheduler] Cancelled task: {} ({})", taskId, entry.description);
        return "Cancelled task " + taskId + ": " + entry.description;
    }

    @Tool(description = "List all scheduled tasks and reminders with their status.")
    public String listScheduledTasks() {
        notifier.notify("Listing scheduled tasks");
        if (tasks.isEmpty()) return "No scheduled tasks.";

        StringBuilder sb = new StringBuilder("Scheduled tasks:\n");
        int i = 1;
        for (ScheduledTaskEntry entry : tasks.values()) {
            sb.append(i++).append(". [").append(entry.status.toUpperCase()).append("] ")
                    .append(entry.type).append(": ").append(entry.description);
            if (entry.interval != null) sb.append(" (every ").append(entry.interval).append(")");
            if (entry.nextFire != null) sb.append(" — next: ").append(entry.nextFire);
            sb.append(" (ID: ").append(entry.id).append(")\n");
        }
        return sb.toString().trim();
    }

    @Tool(description = "Get any fired reminders that haven't been acknowledged yet.")
    public String getFiredReminders() {
        if (firedReminders.isEmpty()) return "No pending reminders.";
        StringBuilder sb = new StringBuilder("Fired reminders:\n");
        int i = 1;
        String r;
        while ((r = firedReminders.poll()) != null) {
            sb.append(i++).append(". ").append(r).append("\n");
        }
        return sb.toString().trim();
    }

    private static class ScheduledTaskEntry {
        final String id;
        final String type;
        final String description;
        final String nextFire;
        final String interval;
        volatile String status;
        final ScheduledFuture<?> future;

        ScheduledTaskEntry(String id, String type, String description,
                           String nextFire, String interval, String status,
                           ScheduledFuture<?> future) {
            this.id = id;
            this.type = type;
            this.description = description;
            this.nextFire = nextFire;
            this.interval = interval;
            this.status = status;
            this.future = future;
        }
    }
}
