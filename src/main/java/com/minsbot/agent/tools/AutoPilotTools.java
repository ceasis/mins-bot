package com.minsbot.agent.tools;

import com.minsbot.agent.AutoPilotService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * AI-callable tools for enabling/disabling auto-pilot mode.
 */
@Component
public class AutoPilotTools {

    private final AutoPilotService autoPilot;
    private final ToolExecutionNotifier notifier;

    public AutoPilotTools(AutoPilotService autoPilot, ToolExecutionNotifier notifier) {
        this.autoPilot = autoPilot;
        this.notifier = notifier;
    }

    @Tool(description = "Enable auto-pilot mode. The bot will watch the screen in the background "
            + "and proactively offer help when it notices something useful. "
            + "Examples: grammar check for emails, debug help for code errors, meeting reminders, "
            + "price comparisons while shopping. Use when the user says 'auto-pilot on', "
            + "'watch my screen and help', 'be proactive', 'help me as I work'.")
    public String enableAutoPilot() {
        notifier.notify("Enabling auto-pilot mode...");
        return autoPilot.start();
    }

    @Tool(description = "Disable auto-pilot mode. Stop watching the screen and offering proactive help. "
            + "Use when the user says 'auto-pilot off', 'stop watching', 'stop being proactive', "
            + "'I don't need help right now'.")
    public String disableAutoPilot() {
        notifier.notify("Disabling auto-pilot mode...");
        return autoPilot.stop();
    }

    @Tool(description = "Check if auto-pilot mode is currently active and get its status.")
    public String autoPilotStatus() {
        var status = autoPilot.getStatus();
        if (!(Boolean) status.get("enabled")) return "Auto-pilot is OFF.";
        return "Auto-pilot is ON. Checking every " + status.get("intervalSeconds") + "s, "
                + "cooldown " + status.get("cooldownSeconds") + "s between suggestions.";
    }

    @Tool(description = "Adjust auto-pilot timing. Change how often it checks the screen "
            + "and how long it waits between suggestions.")
    public String configureAutoPilot(
            @ToolParam(description = "Screen check interval in seconds (5-120)") double intervalSeconds,
            @ToolParam(description = "Minimum cooldown between suggestions in seconds (10-300)") double cooldownSeconds) {
        autoPilot.setIntervalSeconds((int) intervalSeconds);
        autoPilot.setCooldownSeconds((int) cooldownSeconds);
        return "Auto-pilot configured: checking every " + (int) intervalSeconds + "s, "
                + "cooldown " + (int) cooldownSeconds + "s.";
    }
}
