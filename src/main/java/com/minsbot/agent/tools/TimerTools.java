package com.minsbot.agent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * One-shot timer: after a delay, show a system notification (reminder).
 */
@Component
public class TimerTools {

    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "MinsBotTimer");
        t.setDaemon(true);
        return t;
    });

    private final NotificationTools notificationTools;
    private final ToolExecutionNotifier notifier;

    public TimerTools(NotificationTools notificationTools, ToolExecutionNotifier notifier) {
        this.notificationTools = notificationTools;
        this.notifier = notifier;
    }

    @Tool(description = "One-shot SYSTEM NOTIFICATION (OS toast popup) after a delay. NOT a chat reminder. " +
            "Use ONLY when the user explicitly wants a native OS notification — 'pop up a notification in 5 min', " +
            "'show me a toast when the timer ends', 'set a kitchen timer'. " +
            "DO NOT use for 'remind me in 5 minutes to X' (that belongs in chat — use RemindersTools). " +
            "Lost on app restart. Max delay 24 hours.")
    public String setReminder(
            @ToolParam(description = "Delay in minutes (decimals OK, e.g. 5 for 5 minutes, 0.5 for 30 seconds)") double delayMinutes,
            @ToolParam(description = "Title of the reminder notification") String title,
            @ToolParam(description = "Message body for the notification") String message) {
        long delaySec = Math.max(1, Math.round(delayMinutes * 60));
        if (delaySec > 86400) {
            return "Delay must be at most 1440 minutes (24 hours).";
        }
        String t = title != null && !title.isBlank() ? title : "Reminder";
        String m = message != null ? message : "Time's up!";
        notifier.notify("Setting reminder in " + delayMinutes + " minutes");
        scheduler.schedule(() -> notificationTools.showNotification(t, m), delaySec, TimeUnit.SECONDS);
        return "Reminder set for " + delaySec + " second(s). You'll get a notification: \"" + t + "\" — " + m;
    }
}
