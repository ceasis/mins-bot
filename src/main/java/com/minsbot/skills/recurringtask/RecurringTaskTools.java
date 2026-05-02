package com.minsbot.skills.recurringtask;

import com.minsbot.agent.tools.ToolExecutionNotifier;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * @Tool wrappers so the AI can create / list / delete recurring AI-driven tasks from chat.
 */
@Component
public class RecurringTaskTools {

    private final RecurringTaskService service;
    private final ToolExecutionNotifier notifier;

    public RecurringTaskTools(RecurringTaskService service, ToolExecutionNotifier notifier) {
        this.service = service;
        this.notifier = notifier;
    }

    // @Tool removed — duplicate of RemindersTools.createDailyReminder. Method kept
    // for programmatic use by RecurringTaskService and other Java callers.
    public String scheduleDailyAiTask(
            @ToolParam(description = "Time of day when the task should fire — e.g. '8pm', '20:00', '9:30am'") String timeOfDay,
            @ToolParam(description = "Prompt for the AI each day, e.g. 'Give me a short encouraging quote' or 'Share one interesting fun fact'") String prompt,
            @ToolParam(description = "Short human-readable label, e.g. 'Daily encouraging quote at 8pm' (optional)") String label) {
        notifier.notify("Creating daily task at " + timeOfDay);
        try {
            String cron = RecurringTaskService.dailyCronFromTime(timeOfDay);
            String id = service.create(cron, prompt, label);
            return "✅ Daily task created (id: " + id + "). Fires every day at "
                    + timeOfDay + ". Saved to ~/mins_bot_data/recurring_tasks/.";
        } catch (Exception e) {
            return "Failed to create recurring task: " + e.getMessage();
        }
    }

    // @Tool removed — duplicate of RemindersTools.createCronReminder.
    public String scheduleCronAiTask(
            @ToolParam(description = "Spring 6-field cron expression (sec min hour dom month dow)") String cronExpression,
            @ToolParam(description = "Prompt for the AI each time") String prompt,
            @ToolParam(description = "Short label (optional)") String label) {
        notifier.notify("Creating cron task: " + cronExpression);
        try {
            String id = service.create(cronExpression, prompt, label);
            return "✅ Recurring task created (id: " + id + ") with cron '" + cronExpression + "'.";
        } catch (Exception e) {
            return "Failed to create recurring task: " + e.getMessage();
        }
    }

    // @Tool removed — RemindersTools.listReminders shows the same folder plus the
    // scheduled_reports/ folder, giving the user a complete view in one call.
    public String listRecurringTasks() {
        notifier.notify("Listing recurring tasks...");
        var tasks = service.list();
        if (tasks.isEmpty()) return "No recurring tasks yet. Ask me to create one, e.g. 'every 8pm tell me a quote'.";
        StringBuilder sb = new StringBuilder("Your recurring tasks:\n\n");
        for (var t : tasks) {
            sb.append("• **").append(t.label).append("** ")
              .append(t.enabled ? "(active)" : "(disabled)").append("\n")
              .append("  id: ").append(t.id).append("\n")
              .append("  cron: ").append(t.cron).append("\n")
              .append("  prompt: ").append(t.prompt).append("\n");
            if (t.lastFiredAt != null) sb.append("  last fired: ").append(t.lastFiredAt).append("\n");
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    // @Tool removed — RemindersTools.deleteReminder is the canonical surface (by name/slug).
    public String deleteRecurringTask(
            @ToolParam(description = "Task id returned by scheduleDailyAiTask or listRecurringTasks") String id) {
        notifier.notify("Deleting recurring task " + id);
        boolean ok = service.delete(id);
        return ok ? "Deleted task " + id : "No task found with id " + id;
    }

    // @Tool removed — RemindersTools.pauseReminder/resumeReminder is canonical.
    public String setRecurringTaskEnabled(
            @ToolParam(description = "Task id") String id,
            @ToolParam(description = "true to resume, false to pause") boolean enabled) {
        try {
            boolean ok = service.setEnabled(id, enabled);
            return ok ? (enabled ? "Resumed task " + id : "Paused task " + id)
                      : "No task found with id " + id;
        } catch (Exception e) {
            return "Failed: " + e.getMessage();
        }
    }
}
