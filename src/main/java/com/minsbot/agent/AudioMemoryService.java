package com.minsbot.agent;

import com.minsbot.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Periodically captures system audio (speaker output) via ffmpeg dshow (Windows),
 * records WAV clips, transcribes via OpenAI Whisper, and stores timestamped text.
 *
 * Storage: ~/mins_bot_data/audio_memory/YYYY-MM-DD.txt
 * Clips:   ~/mins_bot_data/audio_memory/clips/ (.wav or .mp3 when keep_clips = true)
 * Config:  ~/mins_bot_data/minsbot_config.txt section "## Audio memory"
 *
 * Uses ffmpeg -f dshow to capture (e.g. "Stereo Mix"). FFmpeg is downloaded to
 * ~/mins_bot_data/ffmpeg/ on first use if not present.
 */
@Component
public class AudioMemoryService {

    private static final Logger log = LoggerFactory.getLogger(AudioMemoryService.class);

    private static final Path CONFIG_PATH =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "minsbot_config.txt");

    private static final Path AUDIO_MEMORY_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "audio_memory");

    private static final Path CLIPS_DIR = AUDIO_MEMORY_DIR.resolve("clips");

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter FILE_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    /** "wav" or "mp3". MP3 uses FfmpegProvider (downloads to mins_bot_data/ffmpeg if needed). */
    private static final String FORMAT_WAV = "wav";
    private static final String FORMAT_MP3 = "mp3";

    // Config (mutable, reloaded at runtime)
    private volatile boolean enabled = true;
    private volatile int intervalSeconds = 60;
    private volatile int clipSeconds = 15;
    private volatile boolean keepClips = true;
    private volatile String clipFormat = FORMAT_WAV;
    /** dshow device name for ffmpeg (e.g. "Stereo Mix"). Config: mixer_name in ## Audio memory. */
    private volatile String dshowDevice = "Stereo Mix";

    private volatile String lastText = "";
    private volatile long lastRunMs = 0;
    /** Last ffmpeg stderr when capture failed (e.g. device not found). */
    private volatile String lastCaptureError = null;

    private final ChatService chatService;
    private final FfmpegProvider ffmpegProvider;

    public AudioMemoryService(@org.springframework.context.annotation.Lazy ChatService chatService,
                             FfmpegProvider ffmpegProvider) {
        this.chatService = chatService;
        this.ffmpegProvider = ffmpegProvider;
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(AUDIO_MEMORY_DIR);
        Files.createDirectories(CLIPS_DIR);
        loadConfigFromFile();
        if (enabled && isWindows()) {
            // Resolve (and if needed download) ffmpeg at startup so "what am I listening to?" works on first ask
            Path ffmpeg = ffmpegProvider.getFfmpegPath();
            if (ffmpeg != null) {
                log.info("[AudioMemory] Using ffmpeg dshow for capture (device: {})", dshowDevice);
            } else {
                log.warn("[AudioMemory] FFmpeg is not available. Place ffmpeg.exe in ~/mins_bot_data/ffmpeg/ or set app.ffmpeg.download-url in application.properties.");
            }
        }
    }

    /**
     * Returns a short diagnostic so the bot can explain why audio memory might be empty.
     */
    public String getStatus() {
        if (!isWindows()) {
            return "Audio memory is only supported on Windows (uses ffmpeg dshow to capture system audio).";
        }
        if (!enabled) {
            return "Audio memory is disabled. To capture what you're listening to (e.g. YouTube), enable it in "
                    + CONFIG_PATH + " under section '## Audio memory' with line: enabled: true. Then save and restart Mins Bot.";
        }
        if (ffmpegProvider.getFfmpegPath() == null) {
            return "Audio memory is enabled but ffmpeg is not available yet. It will be downloaded to ~/mins_bot_data/ffmpeg/ on first use. "
                    + "If download fails, set app.ffmpeg.download-url in application.properties or place ffmpeg.exe in that folder manually.";
        }
        String msg = "Audio memory is enabled and uses ffmpeg to capture system audio (device: \"" + dshowDevice + "\"). ";
        if (lastCaptureError != null && !lastCaptureError.isBlank()) {
            msg += "Last capture failed: " + lastCaptureError + ". ";
        }
        msg += "If there are no entries for today yet, say \"capture audio now\" or \"start to capture audio\". ";
        msg += "To fix device errors: ask the bot to \"list audio capture devices\", then set mixer_name under ## Audio memory in " + CONFIG_PATH + " to one of those names.";
        return msg;
    }

    /** Last ffmpeg/capture error message (e.g. device not found). Null if no recent failure. */
    public String getLastCaptureError() { return lastCaptureError; }

    /** Names of dshow audio devices (Windows, requires ffmpeg). Use one as mixer_name in config. */
    public List<String> listCaptureDevices() {
        return ffmpegProvider.listDshowAudioDevices();
    }

    /** Raw ffmpeg output from last list_devices run (for debugging when no devices found). */
    public String getLastListDevicesRawOutput() {
        return ffmpegProvider.getLastListDevicesOutput();
    }

    /** Re-read config. Called by ConfigScanService when minsbot_config.txt changes. */
    public void reloadConfig() {
        loadConfigFromFile();
        log.info("[AudioMemory] Config reloaded — enabled={}, interval={}s, clip={}s, keepClips={}, format={}, dshow={}",
                enabled, intervalSeconds, clipSeconds, keepClips, clipFormat, dshowDevice);
    }

    @Scheduled(fixedDelay = 10000)
    public void tick() {
        if (!enabled || !isWindows()) return;
        long now = System.currentTimeMillis();
        if (now - lastRunMs < intervalSeconds * 1000L) return;
        lastRunMs = now;
        captureAndStore();
    }

    // ═══ Public methods for AudioMemoryTools ═══

    /**
     * Capture system audio immediately, transcribe, and store.
     * Returns the transcribed text, or null on failure.
     */
    public synchronized String captureNow() {
        byte[] wav = captureSystemAudio();
        if (wav == null) return null;

        saveClipIfEnabled(wav);

        String text = transcribe(wav);
        if (text == null || text.isBlank()) return null;

        String collapsed = collapse(text);
        lastText = collapsed;

        appendToDaily(collapsed);
        return collapsed;
    }

    /**
     * Read audio memory text for a specific date (YYYY-MM-DD).
     */
    public String readMemory(String dateStr) {
        Path dayFile = AUDIO_MEMORY_DIR.resolve(dateStr + ".txt");
        if (!Files.exists(dayFile)) return null;
        try {
            return Files.readString(dayFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[AudioMemory] Failed to read {}: {}", dateStr, e.getMessage());
            return null;
        }
    }

    /**
     * List available audio memory dates (file names without extension).
     */
    public String listDates() {
        if (!Files.exists(AUDIO_MEMORY_DIR)) return "No audio memory files yet.";
        try (Stream<Path> files = Files.list(AUDIO_MEMORY_DIR)) {
            StringBuilder sb = new StringBuilder();
            files.filter(p -> p.toString().endsWith(".txt"))
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        String name = p.getFileName().toString().replace(".txt", "");
                        try {
                            long size = Files.size(p);
                            long lines = Files.lines(p).count();
                            sb.append(name).append(" (").append(lines)
                                    .append(" entries, ").append(size / 1024).append("KB)\n");
                        } catch (IOException e) {
                            sb.append(name).append("\n");
                        }
                    });
            return sb.length() == 0 ? "No audio memory files yet." : sb.toString().trim();
        } catch (IOException e) {
            return "Failed to list audio memory: " + e.getMessage();
        }
    }

    // ═══ Internal ═══

    private synchronized void captureAndStore() {
        byte[] wav = captureSystemAudio();
        if (wav == null) return;

        saveClipIfEnabled(wav);

        String text = transcribe(wav);
        if (text == null || text.isBlank()) return;

        String collapsed = collapse(text);

        // Dedup
        if (collapsed.equals(lastText)) return;
        lastText = collapsed;

        appendToDaily(collapsed);
    }

    /**
     * Record clip_seconds of system audio via ffmpeg dshow (Windows only).
     * Returns WAV bytes, or null if ffmpeg unavailable / device not found / silence / error.
     */
    private byte[] captureSystemAudio() {
        if (!isWindows()) return null;
        return captureViaFfmpeg();
    }

    /**
     * Capture system audio using ffmpeg -f dshow (Windows).
     * Auto-detects an available audio device if the configured one isn't found.
     */
    private byte[] captureViaFfmpeg() {
        Path ffmpeg = ffmpegProvider.getFfmpegPath();
        if (ffmpeg == null) {
            lastCaptureError = "ffmpeg not available. It will be downloaded on next startup.";
            return null;
        }
        String device = resolveDevice();
        if (device == null) {
            lastCaptureError = "No audio capture devices found. Enable 'Stereo Mix' in Windows Sound settings "
                    + "(Recording tab → right-click → Show Disabled Devices → enable Stereo Mix).";
            return null;
        }
        lastCaptureError = null;
        try {
            Path wavPath = Files.createTempFile("mins_audio_", ".wav");
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        ffmpeg.toAbsolutePath().toString(),
                        "-y", "-f", "dshow", "-i", "audio=" + device,
                        "-t", String.valueOf(clipSeconds),
                        "-ar", "16000", "-ac", "1",
                        wavPath.toAbsolutePath().toString()
                );
                pb.redirectErrorStream(true);
                Process p = pb.start();
                String stderr = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int exit = p.waitFor();
                if (exit != 0 || !Files.exists(wavPath)) {
                    lastCaptureError = firstLineOrTruncate(stderr, 200);
                    if (lastCaptureError != null && lastCaptureError.isBlank()) lastCaptureError = "ffmpeg exited " + exit;
                    return null;
                }
                byte[] wav = Files.readAllBytes(wavPath);
                if (wav.length == 0 || isSilentWav(wav)) {
                    lastCaptureError = "Capture was silent (no audio level).";
                    return null;
                }
                return wav;
            } finally {
                Files.deleteIfExists(wavPath);
            }
        } catch (Exception e) {
            lastCaptureError = e.getMessage();
            log.debug("[AudioMemory] ffmpeg dshow capture failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Resolve which dshow device to capture from.
     * Uses the configured mixer_name if available, otherwise auto-detects the first audio device.
     */
    private String resolveDevice() {
        String configured = (dshowDevice != null && !dshowDevice.isBlank()) ? dshowDevice.trim() : null;
        List<String> devices = ffmpegProvider.listDshowAudioDevices();
        if (devices.isEmpty()) {
            // No devices found — return configured name anyway (ffmpeg will give a clear error)
            return configured;
        }
        // If the configured device is in the list, use it
        if (configured != null) {
            for (String d : devices) {
                if (d.equalsIgnoreCase(configured)) return d;
            }
            // Configured device not found — auto-select first available
            log.info("[AudioMemory] Configured device '{}' not found. Available: {}. Using '{}'.",
                    configured, devices, devices.get(0));
        }
        return devices.get(0);
    }

    private static String firstLineOrTruncate(String s, int maxLen) {
        if (s == null || s.isBlank()) return null;
        String line = s.lines().filter(l -> !l.isBlank()).findFirst().orElse(s.trim());
        if (line.length() > maxLen) line = line.substring(0, maxLen) + "...";
        return line.trim();
    }

    private boolean isSilentWav(byte[] wav) {
        if (wav.length < 44) return true;
        int threshold = 200;
        for (int i = 44; i < wav.length - 1; i += 2) {
            short sample = (short) ((wav[i + 1] << 8) | (wav[i] & 0xFF));
            if (Math.abs(sample) > threshold) return false;
        }
        return true;
    }

    private String transcribe(byte[] wav) {
        try {
            return chatService.transcribeAudio(wav);
        } catch (Exception e) {
            log.warn("[AudioMemory] Transcription failed: {}", e.getMessage());
            return null;
        }
    }

    private String collapse(String text) {
        String collapsed = text.replaceAll("[\\r\\n]+", " ").trim();
        if (collapsed.length() > 500) collapsed = collapsed.substring(0, 500);
        return collapsed;
    }

    private void appendToDaily(String text) {
        String today = LocalDate.now().format(DATE_FMT);
        String time = LocalTime.now().format(TIME_FMT);
        String entry = "[" + time + "] " + text + "\n";

        Path dayFile = AUDIO_MEMORY_DIR.resolve(today + ".txt");
        try {
            Files.writeString(dayFile, entry, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.debug("[AudioMemory] Stored: [{}] {}...", time,
                    text.substring(0, Math.min(60, text.length())));
        } catch (IOException e) {
            log.warn("[AudioMemory] Write failed: {}", e.getMessage());
        }
    }

    private void saveClipIfEnabled(byte[] wav) {
        if (!keepClips) return;
        try {
            Files.createDirectories(CLIPS_DIR);
            String baseName = LocalDateTime.now().format(FILE_TIME_FMT);
            if (FORMAT_MP3.equalsIgnoreCase(clipFormat)) {
                Path wavPath = Files.createTempFile("mins_audio_", ".wav");
                try {
                    Files.write(wavPath, wav);
                    Path mp3Path = CLIPS_DIR.resolve(baseName + ".mp3");
                    if (convertWavToMp3(wavPath, mp3Path)) {
                        log.debug("[AudioMemory] Saved MP3 clip: {}", mp3Path.getFileName());
                    } else {
                        // Fallback: save as WAV if ffmpeg not available
                        Files.write(CLIPS_DIR.resolve(baseName + ".wav"), wav);
                        log.debug("[AudioMemory] Saved WAV clip (ffmpeg not available): {}", baseName + ".wav");
                    }
                } finally {
                    Files.deleteIfExists(wavPath);
                }
            } else {
                Files.write(CLIPS_DIR.resolve(baseName + ".wav"), wav);
                log.debug("[AudioMemory] Saved WAV clip: {}", baseName + ".wav");
            }
        } catch (IOException e) {
            log.warn("[AudioMemory] Failed to save clip: {}", e.getMessage());
        }
    }

    /**
     * Convert WAV file to MP3 using bundled ffmpeg (~/mins_bot_data/ffmpeg/ffmpeg.exe) or ffmpeg from PATH.
     * Returns true if conversion succeeded, false otherwise.
     */
    private boolean convertWavToMp3(Path wavPath, Path mp3Path) {
        Path ffmpeg = ffmpegProvider.getFfmpegPath();
        String exe = (ffmpeg != null) ? ffmpeg.toAbsolutePath().toString() : "ffmpeg";
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    exe, "-y", "-i", wavPath.toAbsolutePath().toString(),
                    "-codec:a", "libmp3lame", "-q:a", "4",
                    mp3Path.toAbsolutePath().toString()
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            int exit = p.waitFor();
            if (exit != 0) {
                log.warn("[AudioMemory] ffmpeg exited with {} for {}", exit, mp3Path.getFileName());
                return false;
            }
            return Files.exists(mp3Path);
        } catch (Exception e) {
            log.debug("[AudioMemory] ffmpeg not available or failed: {}", e.getMessage());
            return false;
        }
    }

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
                if (!currentSection.equals("## audio memory")) continue;
                if (!trimmed.startsWith("- ")) continue;

                String kv = trimmed.substring(2).trim();
                int colon = kv.indexOf(':');
                if (colon < 0) continue;
                String key = kv.substring(0, colon).trim().toLowerCase();
                String val = kv.substring(colon + 1).trim().toLowerCase();

                switch (key) {
                    case "enabled" -> enabled = val.equals("true");
                    case "interval_seconds" -> {
                        try { intervalSeconds = Math.max(10, Integer.parseInt(val)); }
                        catch (NumberFormatException ignored) {}
                    }
                    case "clip_seconds" -> {
                        try { clipSeconds = Math.max(5, Math.min(60, Integer.parseInt(val))); }
                        catch (NumberFormatException ignored) {}
                    }
                    case "keep_clips" -> keepClips = val.equals("true");
                    case "keep_wav" -> keepClips = val.equals("true"); // backward compat
                    case "clip_format" -> clipFormat = (val.equals("mp3") ? FORMAT_MP3 : FORMAT_WAV);
                    case "mixer_name" -> dshowDevice = (val.isEmpty() ? "Stereo Mix" : kv.substring(colon + 1).trim());
                }
            }
        } catch (IOException e) {
            log.warn("[AudioMemory] Could not read config: {}", e.getMessage());
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
