package com.minsbot.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Resolves ffmpeg executable for audio conversion (e.g. WAV→MP3).
 * Uses ~/mins_bot_data/ffmpeg/ffmpeg.exe if present; otherwise on Windows
 * can download from a configured URL (BtbN FFmpeg-Builds) and extract ffmpeg.exe.
 */
@Component
public class FfmpegProvider {

    private static final Logger log = LoggerFactory.getLogger(FfmpegProvider.class);

    private static final Path FFMPEG_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "ffmpeg");
    private static final Path FFMPEG_EXE = FFMPEG_DIR.resolve("ffmpeg.exe");

    @Value("${app.ffmpeg.download-url:}")
    private String downloadUrl;

    /** Default Windows build (BtbN FFmpeg-Builds, win64 GPL static — self-contained, no DLLs needed). */
    private static final String DEFAULT_DOWNLOAD_URL =
            "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip";

    private volatile Path resolvedPath;
    private volatile boolean downloadAttempted;
    /** Last raw output from list_devices (for debugging when no devices parsed). */
    private volatile String lastListDevicesOutput;

    /**
     * Returns the path to ffmpeg.exe to use (bundled in mins_bot_data, or null if unavailable).
     * Verifies the binary works; re-downloads if the existing exe is broken (e.g. shared build missing DLLs).
     */
    public Path getFfmpegPath() {
        if (resolvedPath != null) return resolvedPath;
        if (Files.exists(FFMPEG_EXE)) {
            if (verifyFfmpeg(FFMPEG_EXE)) {
                resolvedPath = FFMPEG_EXE;
                return resolvedPath;
            }
            // Broken binary (e.g. shared build without DLLs) — delete and re-download
            log.warn("[Ffmpeg] Existing ffmpeg.exe is broken, deleting and re-downloading...");
            try { Files.deleteIfExists(FFMPEG_EXE); } catch (IOException ignored) {}
        }
        if (isWindows() && !downloadAttempted) {
            downloadAttempted = true;
            String url = (downloadUrl != null && !downloadUrl.isBlank()) ? downloadUrl : DEFAULT_DOWNLOAD_URL;
            if (downloadAndExtract(url)) {
                resolvedPath = FFMPEG_EXE;
                return resolvedPath;
            }
        }
        return null;
    }

    /** Run ffmpeg -version and return true if it exits cleanly. */
    private boolean verifyFfmpeg(Path exe) {
        try {
            ProcessBuilder pb = new ProcessBuilder(exe.toAbsolutePath().toString(), "-version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getInputStream().readAllBytes(); // drain output
            int exit = p.waitFor();
            return exit == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** True if we have a local ffmpeg.exe (in mins_bot_data or downloaded). */
    public boolean isAvailable() {
        return getFfmpegPath() != null;
    }

    /**
     * List DirectShow audio device names (Windows only). Use these as mixer_name / audio= in dshow.
     * Runs: ffmpeg -list_devices true -f dshow -i dummy and parses stderr for "..." (audio).
     * Raw output is stored for getLastListDevicesOutput() when the list is empty.
     */
    public List<String> listDshowAudioDevices() {
        Path ffmpeg = getFfmpegPath();
        if (ffmpeg == null || !isWindows()) {
            lastListDevicesOutput = ffmpeg == null ? "ffmpeg not available" : "Windows only";
            return Collections.emptyList();
        }
        ProcessBuilder pb = new ProcessBuilder(
                ffmpeg.toAbsolutePath().toString(),
                "-list_devices", "true", "-f", "dshow", "-i", "dummy"
        );
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            lastListDevicesOutput = out;
            List<String> names = parseAudioDevicesFromListOutput(out);
            return names;
        } catch (Exception e) {
            lastListDevicesOutput = "Error: " + e.getMessage();
            log.debug("[Ffmpeg] list dshow devices failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Parse ffmpeg -list_devices dshow output for audio device names.
     * Handles two formats:
     * 1. Per-line annotation:  "Device Name" (audio)
     * 2. Section-based (most common):
     *      DirectShow audio devices:
     *        "Device Name"
     *          Alternative name "@device_cm_..."
     */
    private static List<String> parseAudioDevicesFromListOutput(String out) {
        List<String> names = new ArrayList<>();
        Pattern quotedName = Pattern.compile("\"([^\"]+)\"");
        boolean inAudioSection = false;

        for (String line : out.split("\\r?\\n")) {
            String lower = line.toLowerCase();

            // Section headers toggle which section we're in
            if (lower.contains("directshow audio devices") || lower.contains("audio devices:")) {
                inAudioSection = true;
                continue;
            }
            if (lower.contains("directshow video devices") || lower.contains("video devices:")) {
                inAudioSection = false;
                continue;
            }

            // Format 1: per-line "(audio)" annotation — works regardless of section
            if (lower.contains("(audio)")) {
                Matcher m = quotedName.matcher(line);
                if (m.find()) {
                    String name = m.group(1).trim();
                    if (!name.isEmpty() && !name.startsWith("@device")) {
                        names.add(name);
                    }
                }
                continue;
            }

            // Format 2: section-based — pick quoted names in the audio section
            if (inAudioSection) {
                if (lower.contains("alternative name")) continue;
                Matcher m = quotedName.matcher(line);
                if (m.find()) {
                    String name = m.group(1).trim();
                    if (!name.isEmpty() && !name.startsWith("@device")) {
                        names.add(name);
                    }
                }
            }
        }
        return names;
    }

    /**
     * List DirectShow video device names (Windows only). Use these as camera_name / video= in dshow.
     * Runs the same command as listDshowAudioDevices but parses the video section instead.
     */
    public List<String> listDshowVideoDevices() {
        Path ffmpeg = getFfmpegPath();
        if (ffmpeg == null || !isWindows()) {
            lastListDevicesOutput = ffmpeg == null ? "ffmpeg not available" : "Windows only";
            return Collections.emptyList();
        }
        ProcessBuilder pb = new ProcessBuilder(
                ffmpeg.toAbsolutePath().toString(),
                "-list_devices", "true", "-f", "dshow", "-i", "dummy"
        );
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            lastListDevicesOutput = out;
            return parseVideoDevicesFromListOutput(out);
        } catch (Exception e) {
            lastListDevicesOutput = "Error: " + e.getMessage();
            log.debug("[Ffmpeg] list dshow video devices failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Parse ffmpeg -list_devices dshow output for video device names.
     * Mirrors parseAudioDevicesFromListOutput but looks for video section / (video) annotation.
     */
    private static List<String> parseVideoDevicesFromListOutput(String out) {
        List<String> names = new ArrayList<>();
        Pattern quotedName = Pattern.compile("\"([^\"]+)\"");
        boolean inVideoSection = false;

        for (String line : out.split("\\r?\\n")) {
            String lower = line.toLowerCase();

            // Section headers toggle which section we're in
            if (lower.contains("directshow video devices") || lower.contains("video devices:")) {
                inVideoSection = true;
                continue;
            }
            if (lower.contains("directshow audio devices") || lower.contains("audio devices:")) {
                inVideoSection = false;
                continue;
            }

            // Format 1: per-line "(video)" annotation
            if (lower.contains("(video)")) {
                Matcher m = quotedName.matcher(line);
                if (m.find()) {
                    String name = m.group(1).trim();
                    if (!name.isEmpty() && !name.startsWith("@device")) {
                        names.add(name);
                    }
                }
                continue;
            }

            // Format 2: section-based — pick quoted names in the video section
            if (inVideoSection) {
                if (lower.contains("alternative name")) continue;
                Matcher m = quotedName.matcher(line);
                if (m.find()) {
                    String name = m.group(1).trim();
                    if (!name.isEmpty() && !name.startsWith("@device")) {
                        names.add(name);
                    }
                }
            }
        }
        return names;
    }

    /** Raw output from the last listDshowAudioDevices() or listDshowVideoDevices() run. Use when list was empty to debug. */
    public String getLastListDevicesOutput() {
        return lastListDevicesOutput;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private boolean downloadAndExtract(String urlString) {
        try {
            Files.createDirectories(FFMPEG_DIR);
            log.info("[Ffmpeg] Downloading from {} ...", urlString);
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(urlString))
                    .GET()
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                log.warn("[Ffmpeg] Download failed: HTTP {}", response.statusCode());
                return false;
            }
            boolean extracted = false;
            try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(response.body()))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String name = entry.getName().replace('\\', '/');
                    if (!entry.isDirectory() && (name.endsWith("bin/ffmpeg.exe") || name.equals("ffmpeg.exe"))) {
                        Files.copy(zis, FFMPEG_EXE, StandardCopyOption.REPLACE_EXISTING);
                        extracted = true;
                        break;
                    }
                    zis.closeEntry();
                }
                if (!extracted || !Files.exists(FFMPEG_EXE)) {
                    log.warn("[Ffmpeg] No ffmpeg.exe found in zip");
                    return false;
                }
            }
            log.info("[Ffmpeg] Installed to {}", FFMPEG_EXE);
            return true;
        } catch (IOException e) {
            log.warn("[Ffmpeg] Download failed: {}", e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[Ffmpeg] Download interrupted");
            return false;
        }
    }
}
