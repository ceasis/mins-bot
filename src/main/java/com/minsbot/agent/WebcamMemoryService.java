package com.minsbot.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Periodically captures photos from the PC webcam and analyzes them via VisionService (GPT-4o).
 * Also supports on-demand video recording (1-minute clips via ffmpeg).
 *
 * Capture: ffmpeg -f dshow -i video="Camera Name" (Windows DirectShow)
 * Photos:  ~/mins_bot_data/webcam_memory/photos/
 * Videos:  ~/mins_bot_data/webcam_memory/videos/
 * Log:     ~/mins_bot_data/webcam_memory/YYYY-MM-DD.txt
 * Config:  ~/mins_bot_data/minsbot_config.txt section "## Webcam memory"
 */
@Component
public class WebcamMemoryService {

    private static final Logger log = LoggerFactory.getLogger(WebcamMemoryService.class);

    private static final Path CONFIG_PATH =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "minsbot_config.txt");

    private static final Path WEBCAM_MEMORY_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "webcam_memory");

    private static final Path PHOTOS_DIR = WEBCAM_MEMORY_DIR.resolve("photos");
    private static final Path VIDEOS_DIR = WEBCAM_MEMORY_DIR.resolve("videos");

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter FILE_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter YEAR_MONTH_FMT = DateTimeFormatter.ofPattern("yyyy_MMM");
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("d");

    // Config (mutable, reloaded at runtime)
    private volatile boolean enabled = true;
    private volatile int intervalSeconds = 5;
    private volatile int videoClipSeconds = 60;
    private volatile boolean keepPhotos = true;
    private volatile boolean keepVideos = true;
    private volatile String cameraName = "";

    private volatile String lastDescription = "";
    private volatile long lastRunMs = 0;
    private volatile String lastCaptureError = null;
    private volatile String resolvedCameraName = null;

    /** When true, camera appears covered (all-black) — poll every 5 minutes instead. */
    private volatile boolean cameraCovered = false;
    private static final int COVERED_INTERVAL_SECONDS = 300; // 5 minutes
    private static final double BLACK_THRESHOLD = 15.0; // avg brightness below this = "black"

    private final FfmpegProvider ffmpegProvider;
    private final VisionService visionService;

    public WebcamMemoryService(FfmpegProvider ffmpegProvider, VisionService visionService) {
        this.ffmpegProvider = ffmpegProvider;
        this.visionService = visionService;
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(WEBCAM_MEMORY_DIR);
        Files.createDirectories(PHOTOS_DIR);
        Files.createDirectories(VIDEOS_DIR);
        loadConfigFromFile();
        if (enabled && isWindows()) {
            ffmpegProvider.getFfmpegPath();
            resolvedCameraName = resolveCamera();
            if (resolvedCameraName != null) {
                log.info("[WebcamMemory] Ready — camera: '{}', interval: {}s", resolvedCameraName, intervalSeconds);
            } else {
                log.info("[WebcamMemory] Enabled but no camera found. Use listCameras() to see available devices.");
            }
        } else if (!enabled) {
            log.info("[WebcamMemory] Disabled (enable in minsbot_config.txt ## Webcam memory)");
        }
    }

    public String getStatus() {
        if (!isWindows()) {
            return "Webcam memory is only supported on Windows.";
        }
        if (!enabled) {
            return "Webcam memory is disabled. Enable it in " + CONFIG_PATH
                    + " under '## Webcam memory' with: enabled: true";
        }
        String cam = resolvedCameraName != null ? resolvedCameraName : "(none detected)";
        String msg = "Webcam memory is enabled. Camera: \"" + cam + "\". ";
        if (cameraCovered) {
            msg += "Camera appears COVERED — polling every " + COVERED_INTERVAL_SECONDS + "s. ";
        } else {
            msg += "Capturing every " + intervalSeconds + "s. ";
        }
        if (lastCaptureError != null && !lastCaptureError.isBlank()) {
            msg += "Last capture error: " + lastCaptureError + ". ";
        }
        msg += "Say \"take a webcam photo\" to test.";
        return msg;
    }

    public String getLastCaptureError() { return lastCaptureError; }

    public List<String> listCameras() {
        return ffmpegProvider.listDshowVideoDevices();
    }

    public String getLastListDevicesRawOutput() {
        return ffmpegProvider.getLastListDevicesOutput();
    }

    public void reloadConfig() {
        loadConfigFromFile();
        if (enabled && isWindows()) {
            resolvedCameraName = resolveCamera();
        }
        log.info("[WebcamMemory] Config reloaded — enabled={}, interval={}s, videoClip={}s, camera='{}'",
                enabled, intervalSeconds, videoClipSeconds,
                resolvedCameraName != null ? resolvedCameraName : cameraName);
    }

    @Scheduled(fixedDelay = 10000)
    public void tick() {
        if (!enabled || !isWindows()) return;
        long now = System.currentTimeMillis();
        int effectiveInterval = cameraCovered ? COVERED_INTERVAL_SECONDS : intervalSeconds;
        if (now - lastRunMs < effectiveInterval * 1000L) return;
        lastRunMs = now;
        captureAndStore();
    }

    // ═══ Public methods for WebcamMemoryTools ═══

    /**
     * Take a photo right now, analyze with Vision API, and return the description.
     */
    public synchronized String captureNow() {
        Path photo = takePhoto();
        if (photo == null) return null;

        String description = analyzePhoto(photo);
        if (description == null || description.isBlank()) {
            description = "Photo captured but Vision analysis unavailable.";
        }
        lastDescription = description;
        appendToDaily(description);

        if (!keepPhotos) {
            try { Files.deleteIfExists(photo); } catch (IOException ignored) {}
        }
        return "Photo: " + photo.toAbsolutePath() + "\n\nDescription:\n" + description;
    }

    /**
     * Record a video clip of the given duration (max 120 seconds).
     */
    public synchronized String recordVideo(int seconds) {
        int duration = Math.max(1, Math.min(120, seconds));
        Path ffmpeg = ffmpegProvider.getFfmpegPath();
        if (ffmpeg == null) {
            lastCaptureError = "ffmpeg not available";
            return "Failed: ffmpeg not available. It will be downloaded on next restart.";
        }
        String cam = ensureCamera();
        if (cam == null) {
            return "Failed: no camera detected. Use listCameraDevices to see available cameras, " +
                    "then set camera_name in minsbot_config.txt under ## Webcam memory.";
        }

        String filename = LocalDateTime.now().format(FILE_TIME_FMT) + "_video.mp4";
        Path output = VIDEOS_DIR.resolve(filename);

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    ffmpeg.toAbsolutePath().toString(),
                    "-f", "dshow",
                    "-vcodec", "mjpeg",
                    "-video_size", "1280x720",
                    "-framerate", "30",
                    "-i", "video=" + cam,
                    "-t", String.valueOf(duration),
                    "-c:v", "libx264",
                    "-crf", "18",
                    "-preset", "ultrafast",
                    "-y",
                    output.toAbsolutePath().toString()
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            // Drain output to prevent blocking
            new Thread(() -> {
                try { p.getInputStream().readAllBytes(); } catch (IOException ignored) {}
            }).start();
            boolean done = p.waitFor(duration + 30, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                lastCaptureError = "Video recording timed out";
                return "Video recording timed out after " + (duration + 30) + "s.";
            }

            if (Files.exists(output) && Files.size(output) > 0) {
                lastCaptureError = null;
                appendToDaily("[Video recorded: " + filename + ", " + duration + "s]");
                return "Video recorded: " + output.toAbsolutePath() + " (" + duration + "s, "
                        + (Files.size(output) / 1024) + " KB)";
            } else {
                lastCaptureError = "Video file empty or not created";
                return "Video recording failed — file was not created. Check camera availability.";
            }
        } catch (Exception e) {
            lastCaptureError = e.getMessage();
            return "Video recording failed: " + e.getMessage();
        }
    }

    public String readMemory(String dateStr) {
        // New path: webcam_memory/2026_Feb/23.txt
        try {
            LocalDate date = LocalDate.parse(dateStr, DATE_FMT);
            Path newPath = WEBCAM_MEMORY_DIR.resolve(date.format(YEAR_MONTH_FMT)).resolve(date.format(DAY_FMT) + ".txt");
            if (Files.exists(newPath)) return Files.readString(newPath, StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
        // Backwards compat: webcam_memory/2026-02-23.txt
        Path oldPath = WEBCAM_MEMORY_DIR.resolve(dateStr + ".txt");
        if (Files.exists(oldPath)) {
            try { return Files.readString(oldPath, StandardCharsets.UTF_8); }
            catch (IOException e) { log.warn("[WebcamMemory] Failed to read {}: {}", dateStr, e.getMessage()); }
        }
        return null;
    }

    public String listDates() {
        if (!Files.exists(WEBCAM_MEMORY_DIR)) return "No webcam memory files yet.";
        try (Stream<Path> files = Files.walk(WEBCAM_MEMORY_DIR)) {
            StringBuilder sb = new StringBuilder();
            files.filter(p -> p.toString().endsWith(".txt"))
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        Path rel = WEBCAM_MEMORY_DIR.relativize(p);
                        String label = rel.getNameCount() == 2
                                ? rel.getName(0) + "/" + rel.getName(1).toString().replace(".txt", "")
                                : p.getFileName().toString().replace(".txt", "");
                        try {
                            long size = Files.size(p);
                            long lines = Files.lines(p).count();
                            sb.append(label).append(" (").append(lines)
                                    .append(" entries, ").append(size / 1024).append("KB)\n");
                        } catch (IOException e) {
                            sb.append(label).append("\n");
                        }
                    });
            return sb.length() == 0 ? "No webcam memory files yet." : sb.toString().trim();
        } catch (IOException e) {
            return "Failed to list webcam memory: " + e.getMessage();
        }
    }

    // ═══ Capture logic ═══

    private synchronized void captureAndStore() {
        Path photo = takePhoto();
        if (photo == null) return;

        // Check if camera is covered (all-black image)
        if (isBlackImage(photo)) {
            if (!cameraCovered) {
                cameraCovered = true;
                log.info("[WebcamMemory] Camera appears covered (black image) — slowing to every {}s", COVERED_INTERVAL_SECONDS);
            }
            // Don't waste Vision API on black images — just delete
            try { Files.deleteIfExists(photo); } catch (IOException ignored) {}
            return;
        }

        // Camera is uncovered — resume normal interval
        if (cameraCovered) {
            cameraCovered = false;
            log.info("[WebcamMemory] Camera uncovered — resuming {}s interval", intervalSeconds);
        }

        String description = analyzePhoto(photo);
        if (description != null && !description.isBlank()) {
            // Dedup: skip if identical to last
            String collapsed = collapse(description);
            if (!collapsed.equals(lastDescription)) {
                lastDescription = collapsed;
                appendToDaily(collapsed);
            }
        }

        if (!keepPhotos) {
            try { Files.deleteIfExists(photo); } catch (IOException ignored) {}
        }
    }

    /**
     * Take a single photo using ffmpeg dshow.
     * Returns the path to the saved JPEG, or null on failure.
     */
    private Path takePhoto() {
        Path ffmpeg = ffmpegProvider.getFfmpegPath();
        if (ffmpeg == null) {
            lastCaptureError = "ffmpeg not available";
            return null;
        }
        String cam = ensureCamera();
        if (cam == null) {
            lastCaptureError = "No camera detected. Set camera_name in minsbot_config.txt or check listCameraDevices.";
            return null;
        }

        LocalDateTime now = LocalDateTime.now();
        // Organize photos: webcam_memory/photos/2026_Feb/23/filename.jpg
        Path dayDir = PHOTOS_DIR
                .resolve(now.format(YEAR_MONTH_FMT))
                .resolve(now.format(DAY_FMT));
        try { Files.createDirectories(dayDir); } catch (IOException ignored) {}

        String filename = now.format(FILE_TIME_FMT) + ".jpg";
        Path output = dayDir.resolve(filename);

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    ffmpeg.toAbsolutePath().toString(),
                    "-f", "dshow",
                    "-vcodec", "mjpeg",
                    "-video_size", "1280x720",
                    "-framerate", "30",
                    "-i", "video=" + cam,
                    "-t", "3",
                    "-update", "1",
                    "-vf", "scale=in_range=limited:out_range=full,format=yuvj420p",
                    "-q:v", "2",
                    "-y",
                    output.toAbsolutePath().toString()
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String stderr = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean done = p.waitFor(15, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                lastCaptureError = "Photo capture timed out (15s)";
                return null;
            }

            if (Files.exists(output) && Files.size(output) > 0) {
                lastCaptureError = null;
                return output;
            } else {
                lastCaptureError = "Photo file empty. ffmpeg output: " + truncate(stderr, 200);
                return null;
            }
        } catch (Exception e) {
            lastCaptureError = e.getMessage();
            return null;
        }
    }

    /**
     * Check if an image is effectively all-black (camera covered).
     * Samples pixels and computes average brightness.
     */
    private boolean isBlackImage(Path imagePath) {
        try {
            BufferedImage img = ImageIO.read(imagePath.toFile());
            if (img == null) return false;
            int w = img.getWidth(), h = img.getHeight();
            long totalBrightness = 0;
            int samples = 0;
            // Sample every 20th pixel for speed
            for (int y = 0; y < h; y += 20) {
                for (int x = 0; x < w; x += 20) {
                    int rgb = img.getRGB(x, y);
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    totalBrightness += (r + g + b) / 3;
                    samples++;
                }
            }
            double avgBrightness = samples > 0 ? (double) totalBrightness / samples : 0;
            return avgBrightness < BLACK_THRESHOLD;
        } catch (IOException e) {
            return false;
        }
    }

    // ═══ Start / Stop (AI-callable) ═══

    /**
     * Start webcam capture. Called by AI tool when user says "start the webcam".
     */
    public String startWebcam() {
        if (!isWindows()) return "Webcam memory is only supported on Windows.";
        if (enabled && !cameraCovered) return "Webcam is already running.";
        enabled = true;
        cameraCovered = false;
        lastRunMs = 0; // trigger immediate capture
        resolvedCameraName = resolveCamera();
        if (resolvedCameraName == null) {
            return "Webcam enabled but no camera found. Use listCameraDevices to see available cameras.";
        }
        log.info("[WebcamMemory] Started by user — camera: '{}', interval: {}s", resolvedCameraName, intervalSeconds);
        return "Webcam started. Camera: \"" + resolvedCameraName + "\", capturing every " + intervalSeconds + "s.";
    }

    /**
     * Stop webcam capture. Called by AI tool when user says "stop the webcam".
     */
    public String stopWebcam() {
        if (!enabled) return "Webcam is already stopped.";
        enabled = false;
        log.info("[WebcamMemory] Stopped by user");
        return "Webcam stopped.";
    }

    /**
     * Analyze a photo using VisionService with a webcam-specific prompt.
     */
    private String analyzePhoto(Path photo) {
        if (!visionService.isAvailable()) return null;
        try {
            return visionService.analyzeWebcamPhoto(photo);
        } catch (Exception e) {
            log.debug("[WebcamMemory] Vision analysis failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Resolve the camera to use: explicit config, or auto-detect the first available.
     */
    private String resolveCamera() {
        if (cameraName != null && !cameraName.isBlank()) {
            return cameraName;
        }
        // Auto-detect first available video device
        List<String> devices = ffmpegProvider.listDshowVideoDevices();
        if (!devices.isEmpty()) {
            String first = devices.get(0);
            log.info("[WebcamMemory] Auto-detected camera: '{}'", first);
            return first;
        }
        return null;
    }

    private String ensureCamera() {
        if (resolvedCameraName == null) {
            resolvedCameraName = resolveCamera();
        }
        return resolvedCameraName;
    }

    // ═══ Storage ═══

    private void appendToDaily(String text) {
        if (text == null || text.isBlank()) return;
        LocalDate today = LocalDate.now();
        String timeStr = LocalDateTime.now().format(TIME_FMT);
        Path monthDir = WEBCAM_MEMORY_DIR.resolve(today.format(YEAR_MONTH_FMT));
        String entry = "[" + timeStr + "] " + truncate(text, 2000) + "\n";
        try {
            Files.createDirectories(monthDir);
            Path dayFile = monthDir.resolve(today.format(DAY_FMT) + ".txt");
            Files.writeString(dayFile, entry, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("[WebcamMemory] Failed to write daily log: {}", e.getMessage());
        }
    }

    // ═══ Config ═══

    private void loadConfigFromFile() {
        if (!Files.exists(CONFIG_PATH)) return;
        try {
            String currentSection = "";
            for (String line : Files.readAllLines(CONFIG_PATH)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("## ")) {
                    currentSection = trimmed.toLowerCase();
                    continue;
                }
                if (!currentSection.equals("## webcam memory")) continue;
                if (!trimmed.startsWith("- ")) continue;

                String kv = trimmed.substring(2).trim();
                int colon = kv.indexOf(':');
                if (colon < 0) continue;
                String key = kv.substring(0, colon).trim().toLowerCase();
                String val = kv.substring(colon + 1).trim().toLowerCase();

                switch (key) {
                    case "enabled" -> enabled = val.equals("true");
                    case "interval_seconds" -> {
                        try { intervalSeconds = Math.max(2, Integer.parseInt(val)); }
                        catch (NumberFormatException ignored) {}
                    }
                    case "video_clip_seconds" -> {
                        try { videoClipSeconds = Math.max(5, Math.min(120, Integer.parseInt(val))); }
                        catch (NumberFormatException ignored) {}
                    }
                    case "keep_photos" -> keepPhotos = val.equals("true");
                    case "keep_videos" -> keepVideos = val.equals("true");
                    case "camera_name" -> cameraName = kv.substring(colon + 1).trim(); // preserve case
                }
            }
        } catch (IOException e) {
            log.warn("[WebcamMemory] Could not read config: {}", e.getMessage());
        }
    }

    private static String collapse(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String s, int max) {
        return (s != null && s.length() > max) ? s.substring(0, max) + "..." : (s != null ? s : "");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
