package com.minsbot.agent.tools;

import com.minsbot.agent.AudioMemoryService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.StringJoiner;
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
            String status = audioMemory.getStatus();
            return "No audio memory found for " + dateStr + ". " + status;
        }
        return "Audio memory for " + dateStr + ":\n" + content;
    }

    @Tool(description = "Capture and transcribe system audio right now. Records a short clip of whatever "
            + "is playing through the speakers, transcribes it, and stores with timestamp. "
            + "Use when the user says 'record it', 'record audio', 'capture audio', 'start to capture audio', "
            + "'start recording audio', 'start capturing', 'what song is this?', or 'remember what\\'s playing'. Prefer calling this over giving config instructions. "
            + "When capture fails, report the exact 'Capture failed: ...' reason to the user.")
    public String captureAudioNow() {
        notifier.notify("Capturing audio memory...");
        String text = audioMemory.captureNow();
        if (text == null || text.isBlank()) {
            String err = audioMemory.getLastCaptureError();
            String status = audioMemory.getStatus();
            if (err != null && !err.isBlank()) {
                return "Capture failed: " + err + " Tell the user this exact reason. " + status;
            }
            return "Could not capture audio memory. " + status;
        }
        return "Captured and stored audio memory:\n" + text;
    }

    @Tool(description = "List all dates that have audio memory recordings. Shows date, entry count, and file size.")
    public String listAudioMemoryDates() {
        notifier.notify("Listing audio memory dates...");
        return audioMemory.listDates();
    }

    @Tool(description = "Check why audio memory might be empty (e.g. feature disabled, or ffmpeg/capture device not set on Windows). "
            + "Call when the user asks what they're listening to or about system audio but there are no recordings, so you can explain how to fix it.")
    public String getAudioMemoryStatus() {
        notifier.notify("Checking audio memory status...");
        return audioMemory.getStatus();
    }

    @Tool(description = "List Windows audio capture device names (DirectShow). Use when capture fails or user asks 'list audio devices' / 'what capture device'. "
            + "Return the list and tell the user to set mixer_name under ## Audio memory in minsbot_config.txt to one of these exact names.")
    public String listAudioCaptureDevices() {
        notifier.notify("Listing audio capture devices...");
        List<String> devices = audioMemory.listCaptureDevices();
        if (devices.isEmpty()) {
            String raw = audioMemory.getLastListDevicesRawOutput();
            String msg = "No audio capture devices found. Enable a loopback device (e.g. Stereo Mix) in Windows: Sound settings → Recording → right-click → Show disabled devices → enable Stereo Mix (or similar).";
            if (raw != null && !raw.isBlank()) {
                String snippet = raw.length() > 600 ? raw.substring(0, 600) + "..." : raw;
                msg += " FFmpeg output (for debugging): " + snippet.replace("\r", " ").replace("\n", " ");
            }
            return msg;
        }
        StringJoiner sj = new StringJoiner("\n", "Available audio capture devices (set mixer_name in minsbot_config.txt ## Audio memory to one of these):\n", "");
        for (String d : devices) sj.add("  - \"" + d + "\"");
        return sj.toString();
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
