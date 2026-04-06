package com.minsbot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/**
 * Video downloader tools: yt-dlp wrapper for YouTube, Twitter, TikTok, Reddit, etc.
 * Auto-installs yt-dlp via winget if missing.
 */
@Component
public class VideoDownloadTools {

    private static final Logger log = LoggerFactory.getLogger(VideoDownloadTools.class);
    private static final Path DOWNLOAD_DIR = Paths.get(
            System.getProperty("user.home"), "mins_bot_data", "videos");

    private final ToolExecutionNotifier notifier;

    public VideoDownloadTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @Tool(description = "Download a video from YouTube, Twitter, TikTok, Reddit, Instagram, or other supported sites. "
            + "Uses yt-dlp. Downloads to ~/mins_bot_data/videos/ by default. "
            + "Use when the user says 'download this video', 'save video from YouTube', etc.")
    public String downloadVideo(
            @ToolParam(description = "Video URL (YouTube, Twitter, TikTok, Reddit, Instagram, etc.)") String url,
            @ToolParam(description = "Optional: output filename without extension, or empty for auto-naming") String filename) {
        notifier.notify("Downloading video: " + url);
        try {
            ensureYtDlp();
            Files.createDirectories(DOWNLOAD_DIR);

            String outputTemplate;
            if (filename != null && !filename.isBlank()) {
                outputTemplate = DOWNLOAD_DIR.resolve(sanitize(filename) + ".%(ext)s").toString();
            } else {
                outputTemplate = DOWNLOAD_DIR.resolve("%(title)s.%(ext)s").toString();
            }

            ProcessBuilder pb = new ProcessBuilder(
                    "yt-dlp",
                    "--no-playlist",
                    "-o", outputTemplate,
                    "--merge-output-format", "mp4",
                    url);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean done = proc.waitFor(300, TimeUnit.SECONDS);
            if (!done) {
                proc.destroyForcibly();
                return "Download timed out after 5 minutes.";
            }
            if (proc.exitValue() != 0) {
                return "Download failed:\n" + lastLines(output, 10);
            }

            // Extract the final filename from yt-dlp output
            String savedPath = extractDestination(output);
            return "Video downloaded successfully!\n"
                    + (savedPath != null ? "Saved to: " + savedPath : "Saved to: " + DOWNLOAD_DIR)
                    + "\n\n" + lastLines(output, 5);
        } catch (Exception e) {
            return "Download failed: " + e.getMessage();
        }
    }

    @Tool(description = "Download only the audio from a video URL as MP3. "
            + "Uses yt-dlp with audio extraction. Great for saving music from YouTube. "
            + "Use when the user says 'download audio', 'save as mp3', 'extract audio from video'.")
    public String downloadAudio(
            @ToolParam(description = "Video URL to extract audio from") String url,
            @ToolParam(description = "Optional: output filename without extension, or empty for auto-naming") String filename) {
        notifier.notify("Downloading audio: " + url);
        try {
            ensureYtDlp();
            Files.createDirectories(DOWNLOAD_DIR);

            String outputTemplate;
            if (filename != null && !filename.isBlank()) {
                outputTemplate = DOWNLOAD_DIR.resolve(sanitize(filename) + ".%(ext)s").toString();
            } else {
                outputTemplate = DOWNLOAD_DIR.resolve("%(title)s.%(ext)s").toString();
            }

            ProcessBuilder pb = new ProcessBuilder(
                    "yt-dlp",
                    "--no-playlist",
                    "-x", "--audio-format", "mp3",
                    "-o", outputTemplate,
                    url);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean done = proc.waitFor(300, TimeUnit.SECONDS);
            if (!done) {
                proc.destroyForcibly();
                return "Download timed out after 5 minutes.";
            }
            if (proc.exitValue() != 0) {
                return "Audio download failed:\n" + lastLines(output, 10);
            }

            String savedPath = extractDestination(output);
            return "Audio downloaded as MP3!\n"
                    + (savedPath != null ? "Saved to: " + savedPath : "Saved to: " + DOWNLOAD_DIR)
                    + "\n\n" + lastLines(output, 5);
        } catch (Exception e) {
            return "Audio download failed: " + e.getMessage();
        }
    }

    @Tool(description = "Get info about a video URL without downloading it: title, duration, resolution options, "
            + "file size, uploader, view count, etc. Use when the user asks 'what video is this?', "
            + "'how long is this video?', or before downloading to show available formats.")
    public String getVideoInfo(
            @ToolParam(description = "Video URL to inspect") String url) {
        notifier.notify("Getting video info: " + url);
        try {
            ensureYtDlp();

            ProcessBuilder pb = new ProcessBuilder(
                    "yt-dlp",
                    "--no-playlist",
                    "--print", "title",
                    "--print", "duration_string",
                    "--print", "uploader",
                    "--print", "view_count",
                    "--print", "upload_date",
                    "--print", "filesize_approx",
                    "--print", "resolution",
                    "--print", "description",
                    "--skip-download",
                    url);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean done = proc.waitFor(30, TimeUnit.SECONDS);
            if (!done) {
                proc.destroyForcibly();
                return "Info fetch timed out.";
            }
            if (proc.exitValue() != 0) {
                return "Failed to get info:\n" + lastLines(output, 5);
            }

            String[] lines = output.trim().split("\n");
            StringBuilder info = new StringBuilder("Video Info:\n");
            String[] labels = {"Title", "Duration", "Uploader", "Views", "Upload Date", "Approx Size", "Resolution", "Description"};
            for (int i = 0; i < Math.min(lines.length, labels.length); i++) {
                String val = lines[i].trim();
                if (!val.isBlank() && !val.equals("NA")) {
                    info.append("  ").append(labels[i]).append(": ").append(val).append("\n");
                }
            }
            return info.toString();
        } catch (Exception e) {
            return "Failed to get video info: " + e.getMessage();
        }
    }

    @Tool(description = "Download a full YouTube playlist as individual video files. "
            + "Use when the user says 'download playlist', 'save all videos from playlist'.")
    public String downloadPlaylist(
            @ToolParam(description = "YouTube playlist URL") String url,
            @ToolParam(description = "Max number of videos to download (0 = all)") double maxVideos) {
        int max = (int) maxVideos;
        notifier.notify("Downloading playlist" + (max > 0 ? " (max " + max + ")" : "") + "...");
        try {
            ensureYtDlp();
            Path playlistDir = DOWNLOAD_DIR.resolve("playlist_" + System.currentTimeMillis());
            Files.createDirectories(playlistDir);

            var cmd = new java.util.ArrayList<String>();
            cmd.add("yt-dlp");
            cmd.add("--yes-playlist");
            cmd.add("-o");
            cmd.add(playlistDir.resolve("%(playlist_index)s - %(title)s.%(ext)s").toString());
            cmd.add("--merge-output-format");
            cmd.add("mp4");
            if (max > 0) {
                cmd.add("--playlist-end");
                cmd.add(String.valueOf(max));
            }
            cmd.add(url);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean done = proc.waitFor(600, TimeUnit.SECONDS);
            if (!done) {
                proc.destroyForcibly();
                return "Playlist download timed out after 10 minutes.";
            }

            // Count downloaded files
            long count;
            try (var files = Files.list(playlistDir)) {
                count = files.filter(Files::isRegularFile).count();
            }
            return "Playlist downloaded: " + count + " videos saved to:\n" + playlistDir
                    + "\n\n" + lastLines(output, 5);
        } catch (Exception e) {
            return "Playlist download failed: " + e.getMessage();
        }
    }

    @Tool(description = "List available format/quality options for a video. Shows resolution, codec, "
            + "file size for each format. Use before downloading to let user pick quality.")
    public String listFormats(
            @ToolParam(description = "Video URL") String url) {
        notifier.notify("Listing formats...");
        try {
            ensureYtDlp();
            ProcessBuilder pb = new ProcessBuilder("yt-dlp", "--list-formats", url);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            proc.waitFor(15, TimeUnit.SECONDS);
            return output.length() > 3000 ? output.substring(0, 3000) + "\n...(truncated)" : output;
        } catch (Exception e) {
            return "Failed: " + e.getMessage();
        }
    }

    @Tool(description = "Check if yt-dlp is installed and show its version. "
            + "If not installed, attempts to install it via winget.")
    public String checkYtDlp() {
        notifier.notify("Checking yt-dlp...");
        try {
            ProcessBuilder pb = new ProcessBuilder("yt-dlp", "--version");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String ver = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            proc.waitFor(5, TimeUnit.SECONDS);
            if (proc.exitValue() == 0) {
                return "yt-dlp is installed. Version: " + ver;
            }
            return installYtDlp();
        } catch (IOException e) {
            return installYtDlp();
        } catch (Exception e) {
            return "Check failed: " + e.getMessage();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════

    private void ensureYtDlp() throws Exception {
        try {
            ProcessBuilder pb = new ProcessBuilder("yt-dlp", "--version");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            proc.getInputStream().readAllBytes();
            proc.waitFor(5, TimeUnit.SECONDS);
            if (proc.exitValue() == 0) return;
        } catch (IOException ignored) {}
        // Not installed — try to install
        String result = installYtDlp();
        if (result.contains("Failed")) throw new RuntimeException(result);
    }

    private String installYtDlp() {
        notifier.notify("Installing yt-dlp via winget...");
        try {
            ProcessBuilder pb = new ProcessBuilder("winget", "install", "yt-dlp.yt-dlp",
                    "--accept-source-agreements", "--accept-package-agreements");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            proc.waitFor(60, TimeUnit.SECONDS);
            if (proc.exitValue() == 0 || output.contains("successfully installed") || output.contains("already installed")) {
                return "yt-dlp installed successfully. You may need to restart the terminal.";
            }
            return "Failed to install yt-dlp via winget. Install manually: winget install yt-dlp.yt-dlp\n" + lastLines(output, 5);
        } catch (Exception e) {
            return "Failed to install yt-dlp: " + e.getMessage();
        }
    }

    private String extractDestination(String output) {
        for (String line : output.split("\n")) {
            // yt-dlp prints: [Merger] Merging formats into "path" or [download] Destination: path
            if (line.contains("Merging formats into") || line.contains("has already been downloaded")
                    || line.contains("[download] Destination:")) {
                int qStart = line.indexOf('"');
                int qEnd = line.lastIndexOf('"');
                if (qStart >= 0 && qEnd > qStart) return line.substring(qStart + 1, qEnd);
                String after = line.contains(":") ? line.substring(line.indexOf(':') + 1).trim() : line;
                return after;
            }
        }
        return null;
    }

    private String sanitize(String name) {
        return name.replaceAll("[<>:\"/\\\\|?*]", "_").trim();
    }

    private String lastLines(String text, int n) {
        String[] lines = text.split("\n");
        int start = Math.max(0, lines.length - n);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            sb.append(lines[i]).append("\n");
        }
        return sb.toString().trim();
    }
}
