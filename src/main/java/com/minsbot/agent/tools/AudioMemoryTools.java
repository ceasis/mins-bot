package com.minsbot.agent.tools;

import com.minsbot.agent.AudioMemoryService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;

/**
 * AI-callable tools for querying audio memory (transcriptions of system speaker audio).
 * Use these when the user asks "what was I listening to?", "what audio was playing?", etc.
 */
@Component
public class AudioMemoryTools {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ToolExecutionNotifier notifier;
    private final AudioMemoryService audioMemory;

    public AudioMemoryTools(ToolExecutionNotifier notifier, AudioMemoryService audioMemory) {
        this.notifier = notifier;
        this.audioMemory = audioMemory;
    }

    @Tool(description = "Get the audio memory (transcriptions of system speaker audio) for a specific date. "
            + "Returns timestamped text entries showing what was playing through speakers throughout the day. "
            + "Use when the user asks 'what was I listening to?', 'what audio was playing?', etc. "
            + "Pass a date like '2026-02-16', or natural words: 'today', 'yesterday', 'last monday', etc.")
    public String getAudioMemory(
            @ToolParam(description = "Date to look up: 'today', 'yesterday', 'last monday', or 'YYYY-MM-DD'") String date) {
        notifier.notify("Reading audio memory: " + date);
        String dateStr = resolveDate(date);
        if (dateStr == null) return "Could not parse date: " + date;

        String content = audioMemory.readMemory(dateStr);
        if (content == null || content.isBlank()) {
            return "No audio memory found for " + dateStr + ".";
        }
        return "Audio memory for " + dateStr + ":\n" + content;
    }

    @Tool(description = "Capture and transcribe system audio right now. Records a short clip of whatever "
            + "is playing through the speakers, transcribes it, and stores with timestamp. "
            + "Use when the user says 'what song is this?', 'remember what\\'s playing', 'capture audio'.")
    public String captureAudioNow() {
        notifier.notify("Capturing audio memory...");
        String text = audioMemory.captureNow();
        if (text == null || text.isBlank()) {
            return "Could not capture audio memory — no system audio available or transcription failed. "
                    + "Make sure Stereo Mix is enabled in Windows Sound settings.";
        }
        return "Captured and stored audio memory:\n" + text;
    }

    @Tool(description = "List all dates that have audio memory recordings. Shows date, entry count, and file size.")
    public String listAudioMemoryDates() {
        notifier.notify("Listing audio memory dates...");
        return audioMemory.listDates();
    }

    // ═══ Date resolution ═══

    private String resolveDate(String input) {
        if (input == null) return null;
        String lower = input.trim().toLowerCase();

        if (lower.matches("\\d{4}-\\d{2}-\\d{2}")) return lower;

        LocalDate today = LocalDate.now();

        return switch (lower) {
            case "today" -> today.format(DATE_FMT);
            case "yesterday" -> today.minusDays(1).format(DATE_FMT);
            default -> {
                if (lower.startsWith("last ")) {
                    String dayName = lower.substring(5).trim();
                    DayOfWeek dow = parseDayOfWeek(dayName);
                    if (dow != null) {
                        yield today.with(TemporalAdjusters.previous(dow)).format(DATE_FMT);
                    }
                }
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
