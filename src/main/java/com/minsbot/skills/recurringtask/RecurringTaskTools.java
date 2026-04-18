package com.minsbot.skills.recurringtask;

import com.minsbot.agent.tools.ToolExecutionNotifier;
import org.springframework.ai.tool.annotation.Tool;
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

    @Tool(description = "Skill: create a recurring DAILY AI-generated task that fires at a specific time of day "
            + "and posts fresh content in the chat. Persists to ~/mins_bot_data/recurring_tasks/ and survives restarts. "
            + "USE THIS when the user says things like 'every 8pm tell me an encouraging quote', "
            + "'daily at 9am give me a fun fact', 'every night at 10pm remind me to stretch', "
            + "'every morning at 7 share a motivational message'. The bot will generate fresh content "
            + "via the AI each time and post it to chat. Returns the task id.")
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

    @Tool(description = "Skill: create a recurring task using a raw Spring cron expression (6-field: "
            + "second minute hour day-of-month month day-of-week). Use when the user wants something more "
            + "complex than once-a-day — e.g. 'every Monday at 9am', 'every 3 hours', 'weekdays at 8:30'. "
            + "Examples: '0 0 9 * * MON-FRI' = weekdays 9am. '0 */30 * * * *' = every 30 minutes.")
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

    @Tool(description = "Skill: list all recurring AI-driven tasks with their id, cron schedule, label, "
            + "enabled status, and last fire time. Use when the user says 'what recurring tasks do I have', "
            + "'show my scheduled tasks', 'list my recurring reminders'.")
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

    @Tool(description = "Skill: delete a recurring task by id. Use when the user says 'delete the quote task', "
            + "'remove my 8pm reminder', 'cancel recurring task rt_xxx'.")
    public String deleteRecurringTask(
            @ToolParam(description = "Task id returned by scheduleDailyAiTask or listRecurringTasks") String id) {
        notifier.notify("Deleting recurring task " + id);
        boolean ok = service.delete(id);
        return ok ? "Deleted task " + id : "No task found with id " + id;
    }

    @Tool(description = "Skill: pause or resume a recurring task without deleting it.")
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
