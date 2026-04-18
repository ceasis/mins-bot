package com.minsbot.skills.upcoming;

import com.minsbot.agent.tools.ToolExecutionNotifier;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * @Tool wrapper so the AI can invoke the Upcoming skill directly.
 */
@Component
public class UpcomingTools {

    private final UpcomingService service;
    private final ToolExecutionNotifier notifier;

    public UpcomingTools(UpcomingService service, ToolExecutionNotifier notifier) {
        this.service = service;
        this.notifier = notifier;
    }

    @Tool(description = "Skill: check everything important for the next N days (default 3). "
            + "Aggregates Google Calendar events, bills due, birthdays, scheduled reminders, and weather "
            + "into a single digest. USE THIS whenever the user asks 'what's coming up', 'what's important "
            + "for the next few days', 'what do I have this week', 'anything I should know about', "
            + "'brief me on the next 3 days', 'what's next', 'upcoming things', or similar. "
            + "Prefer this skill over calling individual calendar/bills/birthday tools separately.")
    public String getUpcomingImportant(
            @ToolParam(description = "Number of days to look ahead (1-14, default 3)") Integer days) {
        notifier.notify("Checking what's important in the next " + (days != null ? days : 3) + " days...");
        return service.buildDigest(days != null ? days : service.getProperties().getDefaultDays());
    }
}
