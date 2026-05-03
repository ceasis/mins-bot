package com.minsbot.agent.tools;

import com.minsbot.skills.archiver.ArchiverConfig;
import com.minsbot.skills.archiver.ArchiverService;
import com.minsbot.skills.filerename.FileRenameConfig;
import com.minsbot.skills.filerename.FileRenameService;
import com.minsbot.skills.logtail.LogTailConfig;
import com.minsbot.skills.logtail.LogTailService;
import com.minsbot.skills.mediactl.MediaCtlConfig;
import com.minsbot.skills.mediactl.MediaCtlService;
import com.minsbot.skills.powerctl.PowerCtlConfig;
import com.minsbot.skills.powerctl.PowerCtlService;
import com.minsbot.skills.screenshotter.ScreenshotterConfig;
import com.minsbot.skills.screenshotter.ScreenshotterService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Bridges the daily-ops skills to the agent so the LLM can take screenshots,
 * pause music, lock screen, zip folders, rename files, and tail logs from chat.
 */
@Component
public class DailyOpsTools {

    @Autowired(required = false) private ScreenshotterService ss;
    @Autowired(required = false) private ScreenshotterConfig.ScreenshotterProperties ssProps;
    @Autowired(required = false) private MediaCtlService mc;
    @Autowired(required = false) private MediaCtlConfig.MediaCtlProperties mcProps;
    @Autowired(required = false) private PowerCtlService pc;
    @Autowired(required = false) private PowerCtlConfig.PowerCtlProperties pcProps;
    @Autowired(required = false) private ArchiverService ar;
    @Autowired(required = false) private ArchiverConfig.ArchiverProperties arProps;
    @Autowired(required = false) private FileRenameService fr;
    @Autowired(required = false) private FileRenameConfig.FileRenameProperties frProps;
    @Autowired(required = false) private LogTailService lt;
    @Autowired(required = false) private LogTailConfig.LogTailProperties ltProps;
    @Autowired(required = false) private ToolExecutionNotifier notifier;

    @Tool(description = "Take a screenshot of the full screen. Use when the user says 'take a screenshot', "
            + "'capture my screen', 'screenshot'. Saves to memory/screenshots/ and returns the path.")
    public String takeScreenshot() {
        if (ss == null || ssProps == null || !ssProps.isEnabled()) return "screenshotter skill is disabled.";
        if (notifier != null) notifier.notify("📸 capturing screen...");
        try {
            Map<String, Object> r = ss.captureFullScreen();
            return "📸 Saved: " + r.get("path") + " (" + r.get("width") + "x" + r.get("height") + ")";
        } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "Take a screenshot across all monitors. Use when the user says 'screenshot all "
            + "screens', 'capture both monitors'.")
    public String screenshotAllScreens() {
        if (ss == null || ssProps == null || !ssProps.isEnabled()) return "screenshotter skill is disabled.";
        try { return "📸 Saved: " + ss.captureAllScreens().get("path"); } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "Control media playback or system volume. Use when the user says 'pause music', "
            + "'play', 'next track', 'previous song', 'volume up/down', 'mute', 'set volume to N'.")
    public String mediaControl(@ToolParam(description = "Action: 'play-pause', 'next', 'prev', 'stop', 'volume-up', 'volume-down', 'mute', or 'set-volume'") String action,
                                @ToolParam(description = "Volume percent (0-100), only for 'set-volume'", required = false) Integer volumePercent) {
        if (mc == null || mcProps == null || !mcProps.isEnabled()) return "mediactl skill is disabled.";
        try {
            return switch (action.toLowerCase()) {
                case "play-pause", "play", "pause" -> "🎵 " + mc.playPause();
                case "next" -> "⏭ " + mc.next();
                case "prev", "previous" -> "⏮ " + mc.prev();
                case "stop" -> "⏹ " + mc.stop();
                case "volume-up" -> "🔊 " + mc.volumeUp();
                case "volume-down" -> "🔉 " + mc.volumeDown();
                case "mute" -> "🔇 " + mc.mute();
                case "set-volume" -> "🔊 " + mc.setVolumePercent(volumePercent == null ? 50 : volumePercent);
                default -> "Unknown action: " + action;
            };
        } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "Lock the screen. Use when the user says 'lock screen', 'lock my computer'.")
    public String lockScreen() {
        if (pc == null || pcProps == null || !pcProps.isEnabled()) return "powerctl skill is disabled.";
        try { return "🔒 " + pc.lock(); } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "Put the computer to sleep. Use when the user says 'sleep my computer', 'go to sleep'.")
    public String sleepComputer() {
        if (pc == null || pcProps == null || !pcProps.isEnabled()) return "powerctl skill is disabled.";
        try { return "💤 " + pc.sleep(); } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "Schedule a shutdown or restart. Use when the user says 'shutdown in 30 minutes', "
            + "'restart in N seconds'. Requires app.skills.powerctl.allow-shutdown=true (off by default).")
    public String scheduleShutdown(@ToolParam(description = "'shutdown' or 'restart'") String action,
                                    @ToolParam(description = "Delay in seconds. Default 60.", required = false) Integer delaySeconds) {
        if (pc == null || pcProps == null || !pcProps.isEnabled()) return "powerctl skill is disabled.";
        int s = delaySeconds == null ? 60 : delaySeconds;
        try {
            return "restart".equalsIgnoreCase(action) ? "↻ " + pc.restart(s) : "⏻ " + pc.shutdown(s);
        } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "Cancel a scheduled shutdown/restart. Use when the user says 'cancel shutdown', "
            + "'abort restart'.")
    public String cancelShutdown() {
        if (pc == null || pcProps == null || !pcProps.isEnabled()) return "powerctl skill is disabled.";
        try { return "✓ " + pc.cancelShutdown(); } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "Zip a file or folder. Use when the user says 'zip folder X', 'compress X', "
            + "'archive X to Y.zip'.")
    public String zipFolder(@ToolParam(description = "Source path (file or folder)") String source,
                             @ToolParam(description = "Destination .zip path") String destZip) {
        if (ar == null || arProps == null || !arProps.isEnabled()) return "archiver skill is disabled.";
        try {
            Map<String, Object> r = ar.zip(source, destZip);
            return "🗜 zipped " + r.get("files") + " files → " + r.get("zipFile") + " (" + r.get("compressedBytes") + " bytes)";
        } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "Extract a zip file. Use when the user says 'unzip X', 'extract X to Y'.")
    public String unzipFile(@ToolParam(description = "Path to .zip") String zip,
                             @ToolParam(description = "Destination folder") String dest) {
        if (ar == null || arProps == null || !arProps.isEnabled()) return "archiver skill is disabled.";
        try { return "📂 " + ar.unzip(zip, dest); } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "Bulk rename files in a folder using regex find/replace. ALWAYS preview first "
            + "with dryRun=true. Use when the user says 'rename all .jpeg to .jpg in X', 'prefix all "
            + "files with date'. Replacement supports {date} token = today's date.")
    public String bulkRename(@ToolParam(description = "Folder path") String path,
                              @ToolParam(description = "Glob filter, e.g. '*.jpeg'", required = false) String glob,
                              @ToolParam(description = "Regex applied to filename") String regex,
                              @ToolParam(description = "Replacement (use {date} for today's date)") String replacement,
                              @ToolParam(description = "Preview only without renaming. Default true.", required = false) Boolean dryRun) {
        if (fr == null || frProps == null || !frProps.isEnabled()) return "filerename skill is disabled.";
        try {
            boolean dry = dryRun == null || dryRun;
            Map<String, Object> r = fr.rename(path, glob, regex, replacement, dry);
            StringBuilder sb = new StringBuilder();
            sb.append(dry ? "🔍 dry run · " : "✓ renamed · ");
            sb.append(r.get("renamed")).append(" changed · ").append(r.get("unchanged")).append(" unchanged · ").append(r.get("failed")).append(" failed\n");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> changes = (List<Map<String, Object>>) r.get("changes");
            int n = Math.min(10, changes.size());
            for (int i = 0; i < n; i++) {
                Map<String, Object> c = changes.get(i);
                sb.append("  ").append(c.get("oldName")).append(" → ").append(c.get("newName")).append("\n");
            }
            if (changes.size() > n) sb.append("  ... and ").append(changes.size() - n).append(" more");
            return sb.toString();
        } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "Show last N lines of a log file with optional regex filter. Use when the user "
            + "says 'tail the log of X', 'show last 100 lines of file Y', 'grep ERROR in log'.")
    public String tailLog(@ToolParam(description = "Path to log file") String path,
                           @ToolParam(description = "Number of lines, default 100", required = false) Integer lines,
                           @ToolParam(description = "Regex filter (only matching lines)", required = false) String filter) {
        if (lt == null || ltProps == null || !ltProps.isEnabled()) return "logtail skill is disabled.";
        try {
            Map<String, Object> r = lt.tail(path, lines == null ? 100 : lines, filter);
            return "📄 " + r.get("lines") + " lines from " + r.get("path") + ":\n\n" + r.get("content");
        } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }
}
