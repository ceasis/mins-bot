package com.minsbot.agent.tools;

import com.minsbot.agent.ProactiveActionService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * AI-callable tools for enabling/disabling proactive action mode.
 * Unlike auto-pilot (which only suggests), proactive action mode actually
 * takes actions: fills forms, clicks buttons, completes tasks, and acts on directives.
 */
@Component
public class ProactiveActionTools {

    private final ProactiveActionService proactiveAction;
    private final ToolExecutionNotifier notifier;

    public ProactiveActionTools(ProactiveActionService proactiveAction,
                                ToolExecutionNotifier notifier) {
        this.proactiveAction = proactiveAction;
        this.notifier = notifier;
    }

    @Tool(description = "Enable proactive action mode. The bot will continuously monitor your screen, "
            + "check pending tasks, and check directives, then take actions automatically. "
            + "Unlike auto-pilot (which only suggests), this mode actually takes action: "
            + "fills forms, clicks buttons, dismisses dialogs, completes tasks. "
            + "Use when the user says 'proactive action on', 'start proactive mode', "
            + "'act on my behalf', 'take action automatically', 'be my jarvis'.")
    public String enableProactiveAction() {
        notifier.notify("Enabling proactive action mode...");
        return proactiveAction.start();
    }

    @Tool(description = "Disable proactive action mode. Stop automatically taking actions. "
            + "Use when the user says 'proactive action off', 'stop proactive actions', "
            + "'stop acting automatically', 'I'll handle things myself'.")
    public String disableProactiveAction() {
        notifier.notify("Disabling proactive action mode...");
        return proactiveAction.stop();
    }

    @Tool(description = "Get the current status of proactive action mode including "
            + "whether it's active, check intervals, and recent action counts.")
    public String proactiveActionStatus() {
        var status = proactiveAction.getStatus();
        if (!(Boolean) status.get("active")) {
            return "Proactive action mode is OFF.";
        }
        return "Proactive action mode is ON. "
                + "Screen check every " + status.get("screenCheckSeconds") + "s, "
                + "task check every " + status.get("taskCheckSeconds") + "s, "
                + "directive check every " + status.get("directiveCheckSeconds") + "s. "
                + "Recent actions: " + status.get("recentActionsCount") + ".";
    }

    @Tool(description = "Configure proactive action intervals. Screen check (how often to look at screen), "
            + "task check (how often to check pending tasks), directive check (how often to check directives). "
            + "All values in seconds.")
    public String configureProactiveAction(
            @ToolParam(description = "Screen check interval in seconds (5-300)") double screenCheckSeconds,
            @ToolParam(description = "Task check interval in seconds (10-600)") double taskCheckSeconds,
            @ToolParam(description = "Directive check interval in seconds (15-900)") double directiveCheckSeconds) {
        proactiveAction.setScreenCheckSeconds((int) screenCheckSeconds);
        proactiveAction.setTaskCheckSeconds((int) taskCheckSeconds);
        proactiveAction.setDirectiveCheckSeconds((int) directiveCheckSeconds);
        return "Proactive action mode configured: screen check every " + (int) screenCheckSeconds + "s, "
                + "task check every " + (int) taskCheckSeconds + "s, "
                + "directive check every " + (int) directiveCheckSeconds + "s.";
    }
}
