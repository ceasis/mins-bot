package com.minsbot.agent.tools;

import com.minsbot.agent.ScreenMemoryService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;

/**
 * AI-callable tools for querying screen memory (OCR text extracted from screenshots).
 * Use these when the user asks "what happened last Monday?", "what was I doing yesterday?", etc.
 */
@Component
public class ScreenMemoryTools {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ToolExecutionNotifier notifier;
    private final ScreenMemoryService screenMemory;

    public ScreenMemoryTools(ToolExecutionNotifier notifier, ScreenMemoryService screenMemory) {
        this.notifier = notifier;
        this.screenMemory = screenMemory;
    }

    @Tool(description = "Get the screen memory (OCR text from screenshots) for a specific date. "
            + "Returns timestamped text entries showing what was on screen throughout the day. "
            + "Use when the user asks 'what happened on Monday?', 'what was I doing yesterday?', 'what am I watching?', etc. "
            + "Pass a date like '2026-02-16', or natural words: 'today', 'yesterday', 'last monday', 'last tuesday', etc.")
    public String getScreenMemory(
            @ToolParam(description = "Date to look up: 'today', 'yesterday', 'last monday', or 'YYYY-MM-DD'") String date) {
        notifier.notify("Reading screen memory: " + date);
        String dateStr = resolveDate(date);
        if (dateStr == null) return "Could not parse date: " + date;

        String content = screenMemory.readMemory(dateStr);
        if (content == null || content.isBlank()) {
            return "No screen memory found for " + dateStr + ".";
        }
        return "Screen memory for " + dateStr + ":\n" + content;
    }

    @Tool(description = "Capture and remember the current screen right now. Takes the latest screenshot, "
            + "runs OCR to extract text, and stores it with a timestamp. Returns the extracted text describing what is on screen. "
            + "Use when the user asks 'what am I looking at?', 'what is on my screen?', 'what do I see right now?', "
            + "'what am I watching?', 'what's on screen?', 'remember what's on screen', 'capture this'. Do NOT use getScreenSize for content questions — use this tool to describe what's visible.")
    public String captureAndRememberNow() {
        notifier.notify("Capturing screen memory...");
        String text = screenMemory.captureNow();
        if (text == null || text.isBlank()) {
            return "Could not capture screen memory — no screenshots available or OCR failed.";
        }
        return "Captured and stored screen memory:\n" + text;
    }

    @Tool(description = "List all dates that have screen memory recordings. Shows date, entry count, and file size.")
    public String listScreenMemoryDates() {
        notifier.notify("Listing screen memory dates...");
        return screenMemory.listDates();
    }

    // ═══ Date resolution ═══

    private String resolveDate(String input) {
        if (input == null) return null;
        String lower = input.trim().toLowerCase();

        // Direct YYYY-MM-DD
        if (lower.matches("\\d{4}-\\d{2}-\\d{2}")) return lower;

        LocalDate today = LocalDate.now();

        return switch (lower) {
            case "today" -> today.format(DATE_FMT);
            case "yesterday" -> today.minusDays(1).format(DATE_FMT);
            default -> {
                // "last monday", "last tuesday", etc.
                if (lower.startsWith("last ")) {
                    String dayName = lower.substring(5).trim();
                    DayOfWeek dow = parseDayOfWeek(dayName);
                    if (dow != null) {
                        yield today.with(TemporalAdjusters.previous(dow)).format(DATE_FMT);
                    }
                }
                // Try parsing as-is
                try {
                    yield LocalDate.parse(lower).format(DATE_FMT);
                } catch (Exception e) {
                    yield null;
                }
            }
        };
    }

    private DayOfWeek parseDayOfWeek(String name) {
        return switch (name) {
            case "monday", "mon" -> DayOfWeek.MONDAY;
            case "tuesday", "tue", "tues" -> DayOfWeek.TUESDAY;
            case "wednesday", "wed" -> DayOfWeek.WEDNESDAY;
            case "thursday", "thu", "thurs" -> DayOfWeek.THURSDAY;
            case "friday", "fri" -> DayOfWeek.FRIDAY;
            case "saturday", "sat" -> DayOfWeek.SATURDAY;
            case "sunday", "sun" -> DayOfWeek.SUNDAY;
            default -> null;
        };
    }
}
