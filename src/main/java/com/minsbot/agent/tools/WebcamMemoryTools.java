package com.minsbot.agent.tools;

import com.minsbot.agent.WebcamMemoryService;
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
 * AI-callable tools for webcam/camera memory (photos + video clips).
 * Use these when the user asks about webcam, camera, "take a photo", "record video", etc.
 */
@Component
public class WebcamMemoryTools {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ToolExecutionNotifier notifier;
    private final WebcamMemoryService webcamMemory;

    public WebcamMemoryTools(ToolExecutionNotifier notifier, WebcamMemoryService webcamMemory) {
        this.notifier = notifier;
        this.webcamMemory = webcamMemory;
    }

    @Tool(description = "Take a photo from the PC webcam right now, analyze it with Vision AI, and return "
            + "a description of what the camera sees (person, surroundings, objects, activity). "
            + "Use when the user says 'take a photo', 'what do you see on camera?', 'webcam capture', "
            + "'show me the camera', 'snap a picture', 'check the camera', etc.")
    public String captureWebcamNow() {
        notifier.notify("Capturing webcam photo...");
        String result = webcamMemory.captureNow();
        if (result == null || result.isBlank()) {
            String err = webcamMemory.getLastCaptureError();
            String status = webcamMemory.getStatus();
            if (err != null && !err.isBlank()) {
                return "Webcam capture failed: " + err + ". " + status;
            }
            return "Could not capture webcam photo. " + status;
        }
        return result;
    }

    @Tool(description = "Record a video clip from the PC webcam. Captures for the specified duration in seconds "
            + "(max 120s, default 60s). Returns the file path when done. "
            + "Use when the user says 'record a video', 'start recording', 'record from camera', "
            + "'film this', 'record 30 seconds', etc.")
    public String recordVideo(
            @ToolParam(description = "Duration in seconds to record (1-120, default 60)") double secondsRaw) {
        int seconds = (int) Math.round(secondsRaw);
        if (seconds <= 0) seconds = 60;
        notifier.notify("Recording " + seconds + "s video...");
        return webcamMemory.recordVideo(seconds);
    }

    @Tool(description = "Get HISTORICAL webcam memory (descriptions from past webcam captures) for a specific date. "
            + "Returns timestamped entries showing what the camera saw throughout the day. "
            + "Use for past queries like 'what did the camera see yesterday?', 'webcam history for Monday'. "
            + "Do NOT use for current webcam — use captureWebcamNow instead. "
            + "Pass a date like '2026-02-16', or natural words: 'today', 'yesterday', 'last monday', etc.")
    public String getWebcamMemory(
            @ToolParam(description = "Date to look up: 'today', 'yesterday', 'last monday', or 'YYYY-MM-DD'") String date) {
        notifier.notify("Reading webcam memory: " + date);
        String dateStr = resolveDate(date);
        if (dateStr == null) return "Could not parse date: " + date;

        String content = webcamMemory.readMemory(dateStr);
        if (content == null || content.isBlank()) {
            return "No webcam memory found for " + dateStr + ". " + webcamMemory.getStatus();
        }
        return "Webcam memory for " + dateStr + ":\n" + content;
    }

    @Tool(description = "List all dates that have webcam memory recordings. Shows date, entry count, and file size.")
    public String listWebcamMemoryDates() {
        notifier.notify("Listing webcam memory dates...");
        return webcamMemory.listDates();
    }

    @Tool(description = "Check webcam memory status: is it enabled, which camera is in use, and any errors. "
            + "Call when webcam capture fails or the user asks about camera setup.")
    public String getWebcamStatus() {
        notifier.notify("Checking webcam status...");
        return webcamMemory.getStatus();
    }

    @Tool(description = "List available camera/webcam devices on this PC. Use when the user asks 'list cameras', "
            + "'what cameras do I have?', or when capture fails and you need to suggest a camera_name. "
            + "Tell the user to set camera_name in minsbot_config.txt under ## Webcam memory.")
    public String listCameraDevices() {
        notifier.notify("Listing camera devices...");
        List<String> devices = webcamMemory.listCameras();
        if (devices.isEmpty()) {
            String raw = webcamMemory.getLastListDevicesRawOutput();
            String msg = "No camera devices found. Make sure a webcam is connected and not in use by another application.";
            if (raw != null && !raw.isBlank()) {
                String snippet = raw.length() > 600 ? raw.substring(0, 600) + "..." : raw;
                msg += " FFmpeg output (for debugging): " + snippet.replace("\r", " ").replace("\n", " ");
            }
            return msg;
        }
        StringJoiner sj = new StringJoiner("\n",
                "Available camera devices (set camera_name in minsbot_config.txt ## Webcam memory to one of these):\n", "");
        for (String d : devices) sj.add("  - \"" + d + "\"");
        return sj.toString();
    }

    @Tool(description = "Start webcam capture. Use when the user says 'start the webcam', 'turn on the camera', "
            + "'enable webcam', 'resume webcam'. Also use this if the camera was in covered/sleep mode and the user "
            + "wants to resume normal capture.")
    public String startWebcam() {
        notifier.notify("Starting webcam...");
        return webcamMemory.startWebcam();
    }

    @Tool(description = "Stop webcam capture. Use when the user says 'stop the webcam', 'turn off the camera', "
            + "'disable webcam', 'pause webcam'.")
    public String stopWebcam() {
        notifier.notify("Stopping webcam...");
        return webcamMemory.stopWebcam();
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
