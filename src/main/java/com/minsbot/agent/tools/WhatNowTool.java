package com.minsbot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * "What should I do right now?" — the single most personal-assistant-y
 * prompt. Combines the next calendar event, pending reminders, and the
 * most recent quick notes into a focused "right now" view that's shorter
 * and more actionable than the full daily briefing.
 */
@Component
public class WhatNowTool {

    private static final Logger log = LoggerFactory.getLogger(WhatNowTool.class);
    private static final Path NOTES_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "quick_notes");
    private static final Path SCHEDULED_REPORTS_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "scheduled_reports");

    @Autowired(required = false) private CalendarTools calendar;
    @Autowired(required = false) private ToolExecutionNotifier notifier;

    @Tool(description = "'What should I do right now?' / 'what's next?' / 'focus'. Returns a short focused "
            + "view of the next calendar event, pending reminders, and 2 most recent notes. "
            + "Shorter than dailyBriefing — meant for mid-day check-ins.")
    public String whatNow() {
        if (notifier != null) notifier.notify("🎯 figuring out what's next...");
        StringBuilder out = new StringBuilder();
        LocalDateTime now = LocalDateTime.now();
        out.append("── now · ").append(now.format(DateTimeFormatter.ofPattern("EEE HH:mm"))).append(" ──\n\n");

        // Next calendar event
        try {
            if (calendar != null) {
                String today = calendar.getTodayEvents();
                out.append("📅 Next: ").append(firstUpcoming(today, now)).append("\n");
            }
        } catch (Exception e) { out.append("📅 (calendar unavailable)\n"); }

        // Pending reminders
        int pending = countScheduledReminders();
        out.append("\n⏰ Pending reminders: ").append(pending).append("\n");

        // Recent notes (top 2)
        List<String> notes = latestNotes(2);
        if (!notes.isEmpty()) {
            out.append("\n📝 Latest notes:\n");
            for (String n : notes) out.append("  • ").append(n).append("\n");
        }

        out.append("\n── ready when you are ──");
        return out.toString();
    }

    private static String firstUpcoming(String todayText, LocalDateTime now) {
        if (todayText == null || todayText.isBlank()) return "nothing scheduled";
        String first = todayText.split("\\R", 2)[0].trim();
        return first.isEmpty() ? "nothing scheduled" : first;
    }

    private static int countScheduledReminders() {
        if (!Files.isDirectory(SCHEDULED_REPORTS_DIR)) return 0;
        try (Stream<Path> s = Files.list(SCHEDULED_REPORTS_DIR)) {
            return (int) s.filter(p -> p.toString().endsWith(".json") || p.toString().endsWith(".yml")).count();
        } catch (IOException e) { return 0; }
    }

    private static List<String> latestNotes(int max) {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(NOTES_DIR)) return out;
        try (Stream<Path> s = Files.list(NOTES_DIR)) {
            s.filter(p -> p.toString().endsWith(".txt"))
             .sorted(Comparator.reverseOrder())
             .limit(max)
             .forEach(p -> {
                 try {
                     String body = Files.readString(p, StandardCharsets.UTF_8).trim();
                     int nl = body.indexOf('\n');
                     String line = nl < 0 ? body : body.substring(0, nl);
                     if (line.length() > 120) line = line.substring(0, 120) + "…";
                     out.add(line);
                 } catch (IOException ignored) {}
             });
        } catch (IOException ignored) {}
        return out;
    }
}
