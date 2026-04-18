package com.minsbot.skills.upcoming;

import com.minsbot.agent.tools.CalendarTools;
import com.minsbot.agent.tools.FinanceTrackerTools;
import com.minsbot.agent.tools.ScheduledTaskTools;
import com.minsbot.agent.tools.SocialMonitorTools;
import com.minsbot.agent.tools.WeatherTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Aggregates what's important for the next N days across calendar, bills,
 * birthdays, reminders, and weather — one unified digest instead of the user
 * asking each service separately.
 */
@Service
public class UpcomingService {

    private static final Logger log = LoggerFactory.getLogger(UpcomingService.class);

    private final UpcomingConfig.UpcomingProperties properties;

    @Autowired(required = false)
    private CalendarTools calendarTools;

    @Autowired(required = false)
    private FinanceTrackerTools financeTrackerTools;

    @Autowired(required = false)
    private SocialMonitorTools socialMonitorTools;

    @Autowired(required = false)
    private ScheduledTaskTools scheduledTaskTools;

    @Autowired(required = false)
    private WeatherTools weatherTools;

    public UpcomingService(UpcomingConfig.UpcomingProperties properties) {
        this.properties = properties;
    }

    public UpcomingConfig.UpcomingProperties getProperties() {
        return properties;
    }

    /**
     * Build the digest. Each section is best-effort: a missing or misconfigured
     * source logs at debug level and is skipped — it never breaks the whole report.
     */
    public String buildDigest(int requestedDays) {
        int days = Math.max(1, Math.min(properties.getMaxDays(),
                requestedDays > 0 ? requestedDays : properties.getDefaultDays()));

        StringBuilder sb = new StringBuilder();
        sb.append("📅 **Important in the next ").append(days).append(" days**\n\n");

        boolean any = false;
        any |= addSection(sb, "🗓️ Calendar events",
                () -> calendarTools != null ? calendarTools.getUpcomingEvents(days) : null);
        any |= addSection(sb, "💰 Bills due",
                () -> financeTrackerTools != null ? financeTrackerTools.getUpcomingBills(days) : null);
        any |= addSection(sb, "🎂 Birthdays",
                () -> socialMonitorTools != null ? socialMonitorTools.checkBirthdays(days) : null);
        any |= addSection(sb, "⏰ Scheduled reminders",
                () -> scheduledTaskTools != null ? scheduledTaskTools.listScheduledTasks() : null);
        any |= addSection(sb, "☀️ Weather",
                () -> weatherTools != null ? weatherTools.getWeather("") : null);

        if (!any) {
            sb.append("_Nothing to report — every source was empty or unavailable._");
        }
        return sb.toString().trim();
    }

    /** Call the supplier, skip-if-blank-or-error, render as a section. Returns true if anything was added. */
    private boolean addSection(StringBuilder sb, String heading, java.util.function.Supplier<String> src) {
        try {
            String content = src.get();
            if (content == null) return false;
            String trimmed = content.trim();
            if (trimmed.isEmpty()) return false;
            // Filter out common "no data" / "not connected" responses so the digest stays tight.
            String low = trimmed.toLowerCase();
            if (low.startsWith("no ") || low.contains("not connected") || low.contains("not configured")
                    || low.contains("no upcoming") || low.contains("no bills") || low.contains("no events")
                    || low.contains("no birthdays") || low.contains("no scheduled")) {
                return false;
            }
            sb.append("### ").append(heading).append("\n").append(trimmed).append("\n\n");
            return true;
        } catch (Exception e) {
            log.debug("[Upcoming] section '{}' failed: {}", heading, e.getMessage());
            return false;
        }
    }
}
