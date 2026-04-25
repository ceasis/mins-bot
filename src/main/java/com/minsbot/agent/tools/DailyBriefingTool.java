package com.minsbot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * One-shot "start of day" assistant: stitches together unread email count,
 * today's calendar, weather, and any active watchers into a single concise
 * report. The LLM doesn't need to chain 5 tool calls to answer "what's
 * happening today?" — this does it in one.
 */
@Component
public class DailyBriefingTool {

    private static final Logger log = LoggerFactory.getLogger(DailyBriefingTool.class);

    @Autowired(required = false) private GmailApiTools gmail;
    @Autowired(required = false) private CalendarTools calendar;
    @Autowired(required = false) private WeatherTools weather;
    @Autowired(required = false) private ToolExecutionNotifier notifier;

    @Tool(description = "Morning briefing / start-of-day summary. Combines unread email count, "
            + "today's calendar events, current weather, and any recent watcher alerts into a "
            + "single concise report. Use for 'good morning', 'brief me', 'what's on today', "
            + "'daily briefing', 'start my day'. One tool call — don't chain getUnreadCount + "
            + "getTodayEvents + getWeather manually.")
    public String dailyBriefing() {
        StringBuilder out = new StringBuilder();
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMM d"));
        out.append("── briefing · ").append(today).append(" ──\n\n");
        if (notifier != null) notifier.notify("☀ building your briefing...");

        // Gmail
        try {
            if (gmail != null) {
                String unread = gmail.getUnreadCount();
                out.append("📨 ").append(oneLine(unread)).append("\n");
            }
        } catch (Exception e) { out.append("📨 (gmail unavailable)\n"); }

        // Calendar
        try {
            if (calendar != null) {
                String today2 = calendar.getTodayEvents();
                out.append("\n📅 Today's calendar:\n").append(indent(today2, "  ")).append("\n");
            }
        } catch (Exception e) { out.append("\n📅 (calendar unavailable)\n"); }

        // Weather
        try {
            if (weather != null) {
                String w = weather.getWeather("");
                out.append("\n🌤 Weather:\n").append(indent(oneLine(w), "  ")).append("\n");
            }
        } catch (Exception e) { out.append("\n🌤 (weather unavailable)\n"); }

        out.append("\n── end briefing ──");
        return out.toString();
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        return s.replace("\n", " · ").trim();
    }

    private static String indent(String s, String pad) {
        if (s == null || s.isBlank()) return pad + "(empty)";
        StringBuilder sb = new StringBuilder();
        for (String l : s.split("\\R")) sb.append(pad).append(l).append('\n');
        return sb.toString().stripTrailing();
    }
}
