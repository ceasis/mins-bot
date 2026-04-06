package com.minsbot.agent.tools;

import com.minsbot.agent.ProactiveEngineService;
import com.minsbot.agent.ProactiveEngineService.ProactiveRule;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * AI-callable tools for managing the proactive engine — toggle on/off,
 * configure quiet hours, manage custom rules, and trigger checks.
 */
@Component
public class ProactiveEngineTools {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ProactiveEngineService engine;
    private final ToolExecutionNotifier notifier;

    public ProactiveEngineTools(ProactiveEngineService engine, ToolExecutionNotifier notifier) {
        this.engine = engine;
        this.notifier = notifier;
    }

    @Tool(description = "Get the current proactive engine status — whether it's enabled, last check time, " +
            "total checks run, total notifications sent, quiet hours, and notification history. " +
            "Use when the user asks about the proactive engine or its status.")
    public String getProactiveStatus() {
        notifier.notify("Checking proactive engine status...");

        StringBuilder sb = new StringBuilder("Proactive Engine Status:\n");
        sb.append("  Enabled: ").append(engine.isEnabled() ? "YES" : "NO").append("\n");
        sb.append("  Quiet hours: ").append(engine.getQuietHoursStart()).append(":00 - ")
                .append(engine.getQuietHoursEnd()).append(":00\n");

        LocalDateTime lastCheck = engine.getLastCheckTime();
        sb.append("  Last check: ").append(lastCheck != null ? lastCheck.format(FMT) : "never").append("\n");
        sb.append("  Total checks: ").append(engine.getTotalCheckCount()).append("\n");
        sb.append("  Total notifications sent: ").append(engine.getTotalNotificationsSent()).append("\n");

        Map<String, LocalDateTime> notifTimes = engine.getLastNotificationTimes();
        if (!notifTimes.isEmpty()) {
            sb.append("\n  Recent notifications:\n");
            notifTimes.forEach((type, time) ->
                    sb.append("    - ").append(type).append(": ").append(time.format(FMT)).append("\n"));
        }

        List<ProactiveRule> rules = engine.getCustomRules();
        if (!rules.isEmpty()) {
            sb.append("\n  Custom rules (").append(rules.size()).append("):\n");
            for (ProactiveRule rule : rules) {
                sb.append("    - [").append(rule.id).append("] every ").append(rule.intervalMinutes)
                        .append(" min: ").append(rule.description).append("\n");
            }
        }

        return sb.toString().trim();
    }

    @Tool(description = "Enable or disable the proactive engine. When enabled, it periodically checks " +
            "the user's context and pushes helpful notifications (break reminders, hydration, morning briefings, etc.). " +
            "Use when the user says 'turn on/off proactive mode' or 'enable/disable proactive notifications'.")
    public String setProactiveEnabled(
            @ToolParam(description = "true to enable, false to disable") boolean enabled) {
        notifier.notify((enabled ? "Enabling" : "Disabling") + " proactive engine...");
        engine.setEnabled(enabled);
        return "Proactive engine " + (enabled ? "ENABLED" : "DISABLED") + ". "
                + (enabled ? "I'll now send helpful notifications based on your context and schedule."
                           : "No more proactive notifications will be sent.");
    }

    @Tool(description = "Set quiet hours for the proactive engine — no notifications will be sent during this period. " +
            "Use when the user says 'don't notify me after 10pm' or 'set quiet hours'.")
    public String setQuietHours(
            @ToolParam(description = "Start hour (0-23), e.g. 22 for 10pm") int startHour,
            @ToolParam(description = "End hour (0-23), e.g. 7 for 7am") int endHour) {
        notifier.notify("Setting quiet hours: " + startHour + ":00 - " + endHour + ":00");
        if (startHour < 0 || startHour > 23 || endHour < 0 || endHour > 23) {
            return "Invalid hours. Use 0-23 range (e.g. 22 for 10pm, 7 for 7am).";
        }
        engine.setQuietHours(startHour, endHour);
        return "Quiet hours set: " + startHour + ":00 - " + endHour + ":00. "
                + "No proactive notifications will be sent during this period.";
    }

    @Tool(description = "Add a custom proactive check rule. The engine will periodically evaluate this rule " +
            "and send a notification if appropriate. Use when the user says 'remind me to check X every Y minutes' " +
            "or 'proactively check on X'.")
    public String addProactiveRule(
            @ToolParam(description = "Description of what to check, e.g. 'Check if stock AAPL is above $200', 'Remind to review pull requests'") String description,
            @ToolParam(description = "How often to check, in minutes (e.g. 60 for hourly, 1440 for daily)") int intervalMinutes) {
        notifier.notify("Adding proactive rule...");
        if (description == null || description.isBlank()) {
            return "Please provide a description for the rule.";
        }
        if (intervalMinutes < 5) intervalMinutes = 5;
        if (intervalMinutes > 10080) intervalMinutes = 10080; // max 1 week

        String id = engine.addCustomRule(description, intervalMinutes);
        return "Custom proactive rule added! ID: " + id + "\n"
                + "Rule: " + description + "\n"
                + "Check interval: every " + intervalMinutes + " minutes\n"
                + "The engine will evaluate this rule and notify you when appropriate.";
    }

    @Tool(description = "Remove a custom proactive rule by its ID. Use when the user wants to stop a custom proactive check.")
    public String removeProactiveRule(
            @ToolParam(description = "The rule ID to remove (e.g. 'rule-1234567890')") String ruleId) {
        notifier.notify("Removing proactive rule: " + ruleId);
        if (ruleId == null || ruleId.isBlank()) {
            return "Please provide the rule ID to remove.";
        }
        boolean removed = engine.removeCustomRule(ruleId);
        return removed
                ? "Proactive rule " + ruleId + " removed successfully."
                : "No rule found with ID: " + ruleId + ". Use listProactiveRules to see active rules.";
    }

    @Tool(description = "List all active proactive rules (both built-in and custom). " +
            "Use when the user asks 'what proactive checks are running' or 'list my proactive rules'.")
    public String listProactiveRules() {
        notifier.notify("Listing proactive rules...");

        StringBuilder sb = new StringBuilder("Proactive Rules:\n\n");

        sb.append("Built-in checks (active when engine is enabled):\n");
        sb.append("  1. Morning briefing — daily at ").append(engine.isEnabled() ? "enabled" : "disabled")
                .append(" (").append("hour ").append(engine.getQuietHoursEnd() + 1).append("+)\n");
        sb.append("  2. Break reminder — every ").append("configured interval").append(" during work hours\n");
        sb.append("  3. Hydration reminder — every ").append("configured interval").append("\n");
        sb.append("  4. Meeting prep — 15 min before events (requires life profile with meeting data)\n");
        sb.append("  5. Bill reminders — morning check for upcoming due dates (requires life profile)\n");
        sb.append("  6. Relationship nudges — evening suggestion to reach out (requires profile contacts)\n");
        sb.append("  7. Goal check-ins — weekly on Sunday/Monday mornings (requires life profile goals)\n");
        sb.append("  8. Weather alerts — morning weather note (requires location in profile)\n");

        List<ProactiveRule> custom = engine.getCustomRules();
        if (custom.isEmpty()) {
            sb.append("\nCustom rules: none\n");
        } else {
            sb.append("\nCustom rules (").append(custom.size()).append("):\n");
            int i = 1;
            for (ProactiveRule rule : custom) {
                sb.append("  ").append(i++).append(". [").append(rule.id).append("] ")
                        .append(rule.description).append(" (every ").append(rule.intervalMinutes).append(" min)\n");
            }
        }

        return sb.toString().trim();
    }

    @Tool(description = "Force an immediate proactive check right now, regardless of the scheduled interval. " +
            "Use when the user says 'run proactive check now' or 'check my context now'.")
    public String triggerProactiveCheck() {
        notifier.notify("Triggering immediate proactive check...");
        engine.triggerImmediateCheck();
        return "Proactive check triggered. Any relevant notifications have been sent to the chat. "
                + "Total notifications sent so far: " + engine.getTotalNotificationsSent();
    }
}
