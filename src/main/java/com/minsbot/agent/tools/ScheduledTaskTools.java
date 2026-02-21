package com.minsbot.agent.tools;

import com.minsbot.agent.AsyncMessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/**
 * AI-callable tools for scheduling reminders and recurring tasks.
 * Recurring tasks auto-update cron_config.txt and push messages to the chat.
 */
@Component
public class ScheduledTaskTools {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskTools.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ToolExecutionNotifier notifier;
    private final AsyncMessageService asyncMessages;
    private final CronConfigTools cronConfigTools;

    /** ChatClient for AI-generated recurring content (quotes, tips, etc.) — null when no API key. */
    @Autowired(required = false)
    private ChatClient chatClient;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<String, ScheduledTaskEntry> tasks = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> firedReminders = new ConcurrentLinkedQueue<>();

    public ScheduledTaskTools(ToolExecutionNotifier notifier,
                              AsyncMessageService asyncMessages,
                              CronConfigTools cronConfigTools) {
        this.notifier = notifier;
        this.asyncMessages = asyncMessages;
        this.cronConfigTools = cronConfigTools;
    }

    @Tool(description = "Schedule a one-shot reminder that fires after a delay (tracked by ID; use listScheduledTasks/cancelScheduledTask). " +
            "Use when the user says 'remind me in 5 minutes to...' and you need list/cancel support.")
    public String scheduleReminder(
            @ToolParam(description = "Reminder message, e.g. 'Take a break'") String message,
            @ToolParam(description = "Delay in minutes before the reminder fires (decimals OK, e.g. 0.5 for 30 seconds)") double delayMinutes) {
        notifier.notify("Setting reminder: " + delayMinutes + "min");
        try {
            if (delayMinutes < 0.1) delayMinutes = 0.1;
            if (delayMinutes > 1440 * 7) delayMinutes = 1440 * 7; // max 7 days
            long delaySec = Math.max(1, Math.round(delayMinutes * 60));

            String id = "rem-" + System.currentTimeMillis();
            LocalDateTime fireTime = LocalDateTime.now().plusSeconds(delaySec);

            ScheduledFuture<?> future = scheduler.schedule(() -> {
                String notification = "REMINDER: " + message;
                firedReminders.add(notification);
                asyncMessages.push(notification);
                notifier.notify(notification);
                log.info("[Scheduler] Reminder fired: {}", message);
                tasks.get(id).status = "fired";
            }, delaySec, TimeUnit.SECONDS);

            tasks.put(id, new ScheduledTaskEntry(id, "reminder", message,
                    fireTime.format(FMT), null, "pending", future));

            // Persist to cron_config.txt
            persistToCron("Reminders", message + " — at " + fireTime.format(FMT));

            log.info("[Scheduler] Reminder set: '{}' fires at {}", message, fireTime.format(FMT));
            return "Reminder set! ID: " + id + ". Will fire at " + fireTime.format(FMT)
                    + " (in " + delaySec + " seconds): " + message;
        } catch (Exception e) {
            return "Failed to set reminder: " + e.getMessage();
        }
    }

    @Tool(description = "Schedule a recurring notification that repeats at a fixed interval. " +
            "Use for simple repeated notifications like 'remind me every hour to stretch'. " +
            "For recurring AI-generated content (quotes, tips, facts), use scheduleRecurringAiTask instead.")
    public String scheduleRecurring(
            @ToolParam(description = "Task description or message to repeat") String description,
            @ToolParam(description = "Interval in seconds between each execution (e.g. 10 for every 10 seconds, 300 for every 5 minutes)") double intervalSeconds) {
        notifier.notify("Scheduling recurring: every " + intervalSeconds + "s");
        try {
            long intervalSec = Math.max(5, Math.round(intervalSeconds)); // min 5 seconds
            if (intervalSec > 86400) intervalSec = 86400; // max daily

            String id = "rec-" + System.currentTimeMillis();
            long interval = intervalSec;

            ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
                firedReminders.add(description);
                asyncMessages.push(description);
                notifier.notify("RECURRING: " + description);
                log.info("[Scheduler] Recurring task fired: {}", description);
            }, interval, interval, TimeUnit.SECONDS);

            String intervalLabel = interval >= 60 ? (interval / 60) + " min" : interval + " sec";
            tasks.put(id, new ScheduledTaskEntry(id, "recurring", description,
                    LocalDateTime.now().plusSeconds(interval).format(FMT),
                    intervalLabel, "active", future));

            // Persist to cron_config.txt
            persistToCron("Other schedule", description + " — every " + intervalLabel);

            log.info("[Scheduler] Recurring task: '{}' every {}", description, intervalLabel);
            return "Recurring task scheduled! ID: " + id + ". Runs every "
                    + intervalLabel + ": " + description;
        } catch (Exception e) {
            return "Failed to schedule recurring task: " + e.getMessage();
        }
    }

    @Tool(description = "Schedule a recurring AI-generated task. Each interval the AI generates fresh content " +
            "and posts it in the chat. Use when the user says things like 'every minute tell me a quote', " +
            "'give me a fun fact every 5 minutes', 'every hour give me a motivational message', etc.")
    public String scheduleRecurringAiTask(
            @ToolParam(description = "Prompt for the AI to generate each interval, e.g. 'Give me a unique inspiring quote', 'Tell me a random fun fact'") String prompt,
            @ToolParam(description = "Interval in seconds between each execution (e.g. 60 for every minute, 300 for every 5 minutes)") double intervalSeconds) {
        notifier.notify("Scheduling recurring AI task: every " + intervalSeconds + "s");
        try {
            if (chatClient == null) {
                return "Cannot schedule AI task — no AI model configured.";
            }
            long intervalSec = Math.max(10, Math.round(intervalSeconds)); // min 10 seconds
            if (intervalSec > 86400) intervalSec = 86400;

            String id = "ai-" + System.currentTimeMillis();
            long interval = intervalSec;

            ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
                try {
                    String content = chatClient.prompt()
                            .system("You are a concise assistant. Respond with ONLY the requested content — no preamble, no explanation, no quotes around it. Keep it to 1-3 sentences max.")
                            .user(prompt)
                            .call()
                            .content();
                    if (content != null && !content.isBlank()) {
                        asyncMessages.push(content.strip());
                        log.info("[Scheduler] AI task delivered: {}", content.strip().substring(0, Math.min(60, content.strip().length())));
                    }
                } catch (Exception e) {
                    log.warn("[Scheduler] AI recurring task failed: {}", e.getMessage());
                }
            }, interval, interval, TimeUnit.SECONDS);

            String intervalLabel = interval >= 60 ? (interval / 60) + " min" : interval + " sec";
            tasks.put(id, new ScheduledTaskEntry(id, "ai-recurring", prompt,
                    LocalDateTime.now().plusSeconds(interval).format(FMT),
                    intervalLabel, "active", future));

            // Persist to cron_config.txt
            persistToCron("Other schedule", prompt + " — every " + intervalLabel);

            log.info("[Scheduler] AI recurring task: '{}' every {}", prompt, intervalLabel);
            return "AI recurring task scheduled! ID: " + id + ". Every "
                    + intervalLabel + " I'll generate: " + prompt;
        } catch (Exception e) {
            return "Failed to schedule AI recurring task: " + e.getMessage();
        }
    }

    @Tool(description = "Cancel a scheduled task or reminder by its ID.")
    public String cancelScheduledTask(
            @ToolParam(description = "The task ID to cancel (e.g. 'rem-1234567890', 'rec-1234567890', or 'ai-1234567890')") String taskId) {
        notifier.notify("Cancelling task: " + taskId);
        ScheduledTaskEntry entry = tasks.get(taskId);
        if (entry == null) return "No task found with ID: " + taskId;
        entry.future.cancel(false);
        entry.status = "cancelled";

        // Remove from cron_config.txt
        String section = "reminder".equals(entry.type) ? "Reminders" : "Other schedule";
        removeFromCron(section, entry.description);

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

    // ═══ Internal ═══

    private void persistToCron(String section, String description) {
        try {
            cronConfigTools.appendCronEntry(section, description);
        } catch (Exception e) {
            log.warn("[Scheduler] Failed to update cron_config.txt: {}", e.getMessage());
        }
    }

    private void removeFromCron(String section, String descriptionSubstring) {
        try {
            cronConfigTools.removeCronEntry(section, descriptionSubstring);
        } catch (Exception e) {
            log.warn("[Scheduler] Failed to update cron_config.txt: {}", e.getMessage());
        }
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
