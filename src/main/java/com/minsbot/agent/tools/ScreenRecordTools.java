package com.minsbot.agent.tools;

import com.minsbot.agent.SystemControlService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Screen recording tools using ffmpeg for desktop capture.
 * Falls back to Windows Game Bar if ffmpeg is not available.
 */
@Component
public class ScreenRecordTools {

    private final SystemControlService systemControl;
    private final ToolExecutionNotifier notifier;

    public ScreenRecordTools(SystemControlService systemControl, ToolExecutionNotifier notifier) {
        this.systemControl = systemControl;
        this.notifier = notifier;
    }

    @Tool(description = "Record the screen for a specified duration and save as a video file. "
            + "Requires ffmpeg to be installed (use installSoftware('Gyan.FFmpeg') to install it). "
            + "Example: recordScreen('C:\\\\Users\\\\user\\\\Videos\\\\recording.mp4', 30)")
    public String recordScreen(
            @ToolParam(description = "Output file path for the recording (e.g. .mp4)") String outputPath,
            @ToolParam(description = "Duration in seconds to record") int durationSeconds) {
        notifier.notify("Recording screen for " + durationSeconds + "s...");

        if (durationSeconds <= 0 || durationSeconds > 600) {
            return "FAILED: Duration must be between 1 and 600 seconds.";
        }

        Path output = Paths.get(outputPath).toAbsolutePath();
        try {
            Files.createDirectories(output.getParent());
        } catch (Exception e) {
            return "FAILED: Cannot create output directory: " + e.getMessage();
        }

        // Check if ffmpeg is available
        String check = systemControl.runCmd("where ffmpeg");
        if (check.contains("Could not find") || check.contains("error")) {
            return "FAILED: ffmpeg not found. Install it first: installSoftware('Gyan.FFmpeg')";
        }

        // Record using ffmpeg gdigrab (Windows desktop capture)
        String cmd = "ffmpeg -f gdigrab -framerate 15 -i desktop -t " + durationSeconds
                + " -c:v libx264 -preset ultrafast -pix_fmt yuv420p \""
                + output.toString() + "\" -y";
        String result = systemControl.runCmd(cmd);

        if (Files.exists(output)) {
            try {
                long sizeKb = Files.size(output) / 1024;
                return "Screen recorded successfully: " + output + " (" + sizeKb + " KB, " + durationSeconds + "s)";
            } catch (Exception e) {
                return "Screen recorded: " + output;
            }
        }
        return "Recording may have failed. ffmpeg output: " + result;
    }

    @Tool(description = "Start recording the screen in the background. Call stopScreenRecording() to stop. "
            + "Requires ffmpeg. The recording runs until manually stopped.")
    public String startScreenRecording(
            @ToolParam(description = "Output file path for the recording (e.g. .mp4)") String outputPath) {
        notifier.notify("Starting screen recording...");

        Path output = Paths.get(outputPath).toAbsolutePath();
        try {
            Files.createDirectories(output.getParent());
        } catch (Exception e) {
            return "FAILED: Cannot create output directory: " + e.getMessage();
        }

        String check = systemControl.runCmd("where ffmpeg");
        if (check.contains("Could not find") || check.contains("error")) {
            return "FAILED: ffmpeg not found. Install it first: installSoftware('Gyan.FFmpeg')";
        }

        // Start ffmpeg in background
        String cmd = "start /B ffmpeg -f gdigrab -framerate 15 -i desktop "
                + "-c:v libx264 -preset ultrafast -pix_fmt yuv420p \""
                + output.toString() + "\" -y";
        systemControl.runCmd(cmd);
        return "Screen recording started. Output: " + output + ". Call stopScreenRecording() to stop.";
    }

    @Tool(description = "Stop an ongoing screen recording started by startScreenRecording().")
    public String stopScreenRecording() {
        notifier.notify("Stopping screen recording...");
        return systemControl.runCmd("taskkill /IM ffmpeg.exe /F");
    }

    @Tool(description = "Open Windows Game Bar for screen recording (built-in, no ffmpeg needed). "
            + "Press Win+Alt+R in Game Bar to start/stop recording.")
    public String openGameBar() {
        notifier.notify("Opening Windows Game Bar...");
        return systemControl.runCmd("start ms-gamebar:");
    }
}
