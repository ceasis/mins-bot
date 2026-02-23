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
 * Periodically captures system audio (speaker output) and transcribes via OpenAI Whisper.
 *
 * Capture strategy (Windows):
 *   1. WASAPI loopback — captures speaker output directly (no Stereo Mix needed)
 *   2. ffmpeg dshow fallback — uses Stereo Mix or other dshow audio device
 *
 * The WASAPI helper is a tiny C# program compiled on-the-fly with csc.exe (.NET Framework).
 * ffmpeg then converts the captured WAV to 16kHz mono for Whisper.
 *
 * Storage: ~/mins_bot_data/audio_memory/YYYY-MM-DD.txt
 * Clips:   ~/mins_bot_data/audio_memory/clips/
 * Config:  ~/mins_bot_data/minsbot_config.txt section "## Audio memory"
 */
@Component
public class AudioMemoryService {

    private static final Logger log = LoggerFactory.getLogger(AudioMemoryService.class);

    private static final Path CONFIG_PATH =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "minsbot_config.txt");

    private static final Path AUDIO_MEMORY_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "audio_memory");

    private static final Path CLIPS_DIR = AUDIO_MEMORY_DIR.resolve("clips");

    private static final Path FFMPEG_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "ffmpeg");
    private static final Path WASAPI_EXE = FFMPEG_DIR.resolve("wasapi_capture.exe");
    private static final Path WASAPI_CS  = FFMPEG_DIR.resolve("wasapi_capture.cs");

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter FILE_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter YEAR_MONTH_FMT = DateTimeFormatter.ofPattern("yyyy_MMM");
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("d");

    private static final String FORMAT_WAV = "wav";
    private static final String FORMAT_MP3 = "mp3";

    // Config (mutable, reloaded at runtime)
    private volatile boolean enabled = true;
    private volatile int intervalSeconds = 60;
    private volatile int clipSeconds = 15;
    private volatile boolean keepClips = true;
    private volatile String clipFormat = FORMAT_WAV;
    private volatile String dshowDevice = "Stereo Mix";

    private volatile String lastText = "";
    private volatile long lastRunMs = 0;
    private volatile String lastCaptureError = null;
    private volatile boolean wasapiAvailable = false;

    private final ChatService chatService;
    private final FfmpegProvider ffmpegProvider;
    private final PlaylistService playlistService;

    public AudioMemoryService(@org.springframework.context.annotation.Lazy ChatService chatService,
                             FfmpegProvider ffmpegProvider,
                             @org.springframework.context.annotation.Lazy PlaylistService playlistService) {
        this.chatService = chatService;
        this.ffmpegProvider = ffmpegProvider;
        this.playlistService = playlistService;
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(AUDIO_MEMORY_DIR);
        Files.createDirectories(CLIPS_DIR);
        loadConfigFromFile();
        if (enabled && isWindows()) {
            // Build WASAPI capture helper (compile C# on first use)
            wasapiAvailable = buildWasapiCapture();
            if (wasapiAvailable) {
                log.info("[AudioMemory] WASAPI loopback capture ready (no Stereo Mix needed)");
            } else {
                log.info("[AudioMemory] WASAPI capture not available, will use ffmpeg dshow (device: {})", dshowDevice);
            }
            // Ensure ffmpeg is available (for WAV conversion and dshow fallback)
            ffmpegProvider.getFfmpegPath();
        }
    }

    public String getStatus() {
        if (!isWindows()) {
            return "Audio memory is only supported on Windows.";
        }
        if (!enabled) {
            return "Audio memory is disabled. Enable it in " + CONFIG_PATH
                    + " under '## Audio memory' with: enabled: true";
        }
        String method = wasapiAvailable ? "WASAPI loopback (captures speaker output directly)"
                : "ffmpeg dshow (device: \"" + dshowDevice + "\")";
        String msg = "Audio memory is enabled. Capture method: " + method + ". ";
        if (lastCaptureError != null && !lastCaptureError.isBlank()) {
            msg += "Last capture error: " + lastCaptureError + ". ";
        }
        msg += "Say \"capture audio now\" to test.";
        return msg;
    }

    public String getLastCaptureError() { return lastCaptureError; }

    public List<String> listCaptureDevices() {
        return ffmpegProvider.listDshowAudioDevices();
    }

    public String getLastListDevicesRawOutput() {
        return ffmpegProvider.getLastListDevicesOutput();
    }

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

    public synchronized String captureNow() {
        byte[] wav = captureSystemAudio();
        if (wav == null) return null;

        saveClipIfEnabled(wav);

        String text = transcribe(wav);
        if (text == null || text.isBlank()) return null;

        String collapsed = collapse(text);
        lastText = collapsed;

        appendToDaily(collapsed);

        // Auto-detect music and save to playlist
        if (playlistService != null && playlistService.isEnabled()) {
            try { playlistService.classifyAndSave(collapsed); }
            catch (Exception e) { log.debug("[AudioMemory] Playlist classification failed: {}", e.getMessage()); }
        }

        return collapsed;
    }

    public String readMemory(String dateStr) {
        // New path: audio_memory/2026_Feb/23.txt
        Path newPath = resolveDayFile(AUDIO_MEMORY_DIR, dateStr);
        if (newPath != null && Files.exists(newPath)) {
            try { return Files.readString(newPath, StandardCharsets.UTF_8); }
            catch (IOException e) { log.warn("[AudioMemory] Failed to read {}: {}", dateStr, e.getMessage()); return null; }
        }
        // Backwards compat: audio_memory/2026-02-23.txt
        Path oldPath = AUDIO_MEMORY_DIR.resolve(dateStr + ".txt");
        if (Files.exists(oldPath)) {
            try { return Files.readString(oldPath, StandardCharsets.UTF_8); }
            catch (IOException e) { log.warn("[AudioMemory] Failed to read {}: {}", dateStr, e.getMessage()); return null; }
        }
        return null;
    }

    public String listDates() {
        if (!Files.exists(AUDIO_MEMORY_DIR)) return "No audio memory files yet.";
        try (Stream<Path> files = Files.walk(AUDIO_MEMORY_DIR)) {
            StringBuilder sb = new StringBuilder();
            files.filter(p -> p.toString().endsWith(".txt"))
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        String label = dayFileLabel(AUDIO_MEMORY_DIR, p);
                        try {
                            long size = Files.size(p);
                            long lines = Files.lines(p).count();
                            sb.append(label).append(" (").append(lines)
                                    .append(" entries, ").append(size / 1024).append("KB)\n");
                        } catch (IOException e) {
                            sb.append(label).append("\n");
                        }
                    });
            return sb.length() == 0 ? "No audio memory files yet." : sb.toString().trim();
        } catch (IOException e) {
            return "Failed to list audio memory: " + e.getMessage();
        }
    }

    // ═══ Capture logic ═══

    private synchronized void captureAndStore() {
        byte[] wav = captureSystemAudio();
        if (wav == null) return;

        saveClipIfEnabled(wav);

        String text = transcribe(wav);
        if (text == null || text.isBlank()) return;

        String collapsed = collapse(text);
        if (collapsed.equals(lastText)) return;
        lastText = collapsed;

        appendToDaily(collapsed);

        // Auto-detect music and save to playlist
        if (playlistService != null && playlistService.isEnabled()) {
            try { playlistService.classifyAndSave(collapsed); }
            catch (Exception e) { log.debug("[AudioMemory] Playlist classification failed: {}", e.getMessage()); }
        }
    }

    /**
     * Capture system audio. Tries WASAPI loopback first (direct speaker tap),
     * falls back to ffmpeg dshow (Stereo Mix).
     */
    private byte[] captureSystemAudio() {
        if (!isWindows()) return null;

        // Try WASAPI loopback first — captures speaker output without Stereo Mix
        if (wasapiAvailable) {
            byte[] wav = captureViaWasapi();
            if (wav != null) return wav;
            log.debug("[AudioMemory] WASAPI capture failed, trying ffmpeg dshow fallback");
        }

        // Fallback: ffmpeg dshow
        return captureViaFfmpeg();
    }

    // ═══ WASAPI loopback capture ═══

    /**
     * Capture system audio via WASAPI loopback using the compiled C# helper.
     * The helper captures in the system's native format (typically 48kHz float stereo),
     * then ffmpeg converts to 16kHz mono 16-bit PCM for Whisper.
     */
    private byte[] captureViaWasapi() {
        if (!Files.exists(WASAPI_EXE)) return null;
        Path ffmpeg = ffmpegProvider.getFfmpegPath();
        if (ffmpeg == null) return null;

        try {
            // Step 1: Capture raw system audio via WASAPI loopback
            Path rawWav = Files.createTempFile("mins_wasapi_raw_", ".wav");
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        WASAPI_EXE.toAbsolutePath().toString(),
                        String.valueOf(clipSeconds),
                        rawWav.toAbsolutePath().toString()
                );
                pb.redirectErrorStream(true);
                Process p = pb.start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int exit = p.waitFor();
                if (exit != 0) {
                    lastCaptureError = "WASAPI capture failed: " + out.trim();
                    log.debug("[AudioMemory] WASAPI capture exit {}: {}", exit, out.trim());
                    return null;
                }
                if (!Files.exists(rawWav) || Files.size(rawWav) < 100) {
                    lastCaptureError = "WASAPI capture produced no data";
                    return null;
                }

                // Step 2: Convert to 16kHz mono 16-bit PCM WAV (Whisper-compatible)
                Path converted = Files.createTempFile("mins_audio_", ".wav");
                try {
                    pb = new ProcessBuilder(
                            ffmpeg.toAbsolutePath().toString(),
                            "-y", "-i", rawWav.toAbsolutePath().toString(),
                            "-ar", "16000", "-ac", "1", "-sample_fmt", "s16",
                            converted.toAbsolutePath().toString()
                    );
                    pb.redirectErrorStream(true);
                    p = pb.start();
                    p.getInputStream().readAllBytes();
                    p.waitFor();

                    byte[] wav = Files.readAllBytes(converted);
                    if (wav.length < 44 || isSilentWav(wav)) {
                        lastCaptureError = "WASAPI capture was silent (no audio playing)";
                        return null;
                    }
                    lastCaptureError = null;
                    return wav;
                } finally {
                    Files.deleteIfExists(converted);
                }
            } finally {
                Files.deleteIfExists(rawWav);
            }
        } catch (Exception e) {
            lastCaptureError = "WASAPI error: " + e.getMessage();
            log.debug("[AudioMemory] WASAPI capture exception: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Build the WASAPI loopback capture C# helper.
     * Writes the source, compiles with csc.exe, returns true if the exe was created.
     */
    private boolean buildWasapiCapture() {
        if (Files.exists(WASAPI_EXE)) return true;
        try {
            Path csc = findCsc();
            if (csc == null) {
                log.info("[AudioMemory] csc.exe not found (.NET Framework required for WASAPI capture)");
                return false;
            }

            Files.createDirectories(FFMPEG_DIR);
            Files.writeString(WASAPI_CS, WASAPI_CAPTURE_CS, StandardCharsets.UTF_8);

            ProcessBuilder pb = new ProcessBuilder(
                    csc.toAbsolutePath().toString(),
                    "/out:" + WASAPI_EXE.toAbsolutePath(),
                    "/target:exe",
                    "/platform:x64",
                    "/optimize+",
                    "/nologo",
                    WASAPI_CS.toAbsolutePath().toString()
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = p.waitFor();
            if (exit != 0) {
                log.warn("[AudioMemory] Failed to compile wasapi_capture.cs: {}", output.trim());
                return false;
            }
            return Files.exists(WASAPI_EXE);
        } catch (Exception e) {
            log.warn("[AudioMemory] Could not build WASAPI capture tool: {}", e.getMessage());
            return false;
        }
    }

    private static Path findCsc() {
        // .NET Framework 4.x (always on Windows 10+)
        Path csc64 = Path.of("C:\\Windows\\Microsoft.NET\\Framework64\\v4.0.30319\\csc.exe");
        if (Files.exists(csc64)) return csc64;
        Path csc32 = Path.of("C:\\Windows\\Microsoft.NET\\Framework\\v4.0.30319\\csc.exe");
        if (Files.exists(csc32)) return csc32;
        return null;
    }

    // ═══ ffmpeg dshow capture (fallback) ═══

    private byte[] captureViaFfmpeg() {
        Path ffmpeg = ffmpegProvider.getFfmpegPath();
        if (ffmpeg == null) {
            lastCaptureError = "ffmpeg not available.";
            return null;
        }
        String device = resolveDevice();
        if (device == null) {
            lastCaptureError = "No audio capture devices found. Enable 'Stereo Mix' in Windows Sound settings.";
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

    private String resolveDevice() {
        String configured = (dshowDevice != null && !dshowDevice.isBlank()) ? dshowDevice.trim() : null;
        List<String> devices = ffmpegProvider.listDshowAudioDevices();
        if (devices.isEmpty()) return configured;
        if (configured != null) {
            for (String d : devices) {
                if (d.equalsIgnoreCase(configured)) return d;
            }
            log.info("[AudioMemory] Configured device '{}' not found. Available: {}. Using '{}'.",
                    configured, devices, devices.get(0));
        }
        return devices.get(0);
    }

    // ═══ Helpers ═══

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
        LocalDate today = LocalDate.now();
        String time = LocalTime.now().format(TIME_FMT);
        String entry = "[" + time + "] " + text + "\n";

        Path monthDir = AUDIO_MEMORY_DIR.resolve(today.format(YEAR_MONTH_FMT));
        try {
            Files.createDirectories(monthDir);
            Path dayFile = monthDir.resolve(today.format(DAY_FMT) + ".txt");
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
            LocalDateTime now = LocalDateTime.now();
            Path dayDir = CLIPS_DIR
                    .resolve(now.format(YEAR_MONTH_FMT))
                    .resolve(now.format(DAY_FMT));
            Files.createDirectories(dayDir);
            String baseName = now.format(FILE_TIME_FMT);
            if (FORMAT_MP3.equalsIgnoreCase(clipFormat)) {
                Path wavPath = Files.createTempFile("mins_audio_", ".wav");
                try {
                    Files.write(wavPath, wav);
                    Path mp3Path = dayDir.resolve(baseName + ".mp3");
                    if (convertWavToMp3(wavPath, mp3Path)) {
                        log.debug("[AudioMemory] Saved MP3 clip: {}", mp3Path.getFileName());
                    } else {
                        Files.write(dayDir.resolve(baseName + ".wav"), wav);
                    }
                } finally {
                    Files.deleteIfExists(wavPath);
                }
            } else {
                Files.write(dayDir.resolve(baseName + ".wav"), wav);
            }
        } catch (IOException e) {
            log.warn("[AudioMemory] Failed to save clip: {}", e.getMessage());
        }
    }

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
            return exit == 0 && Files.exists(mp3Path);
        } catch (Exception e) {
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
                    case "keep_wav" -> keepClips = val.equals("true");
                    case "clip_format" -> clipFormat = (val.equals("mp3") ? FORMAT_MP3 : FORMAT_WAV);
                    case "mixer_name" -> dshowDevice = (val.isEmpty() ? "Stereo Mix" : kv.substring(colon + 1).trim());
                }
            }
        } catch (IOException e) {
            log.warn("[AudioMemory] Could not read config: {}", e.getMessage());
        }
    }

    /** Resolve "2026-02-23" → baseDir/2026_Feb/23.txt */
    private static Path resolveDayFile(Path baseDir, String dateStr) {
        try {
            LocalDate date = LocalDate.parse(dateStr, DATE_FMT);
            return baseDir.resolve(date.format(YEAR_MONTH_FMT)).resolve(date.format(DAY_FMT) + ".txt");
        } catch (Exception e) { return null; }
    }

    /** Build a display label from a day file path (e.g. "2026_Feb/23.txt" → "2026-02-23"). */
    private static String dayFileLabel(Path baseDir, Path file) {
        Path rel = baseDir.relativize(file);
        // New structure: 2026_Feb/23.txt
        if (rel.getNameCount() == 2) {
            String monthDir = rel.getName(0).toString(); // "2026_Feb"
            String dayFile = rel.getName(1).toString().replace(".txt", ""); // "23"
            return monthDir + "/" + dayFile;
        }
        // Old flat structure: 2026-02-23.txt
        return file.getFileName().toString().replace(".txt", "");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    // ═══ C# WASAPI loopback capture source ═══

    private static final String WASAPI_CAPTURE_CS = """
using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;

class Program {
    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    class MMDeviceEnumerator { }

    [Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDeviceEnumerator {
        int _EnumAudioEndpoints();
        [PreserveSig]
        int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice endpoint);
    }

    [Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDevice {
        [PreserveSig]
        int Activate(ref Guid iid, uint clsCtx, IntPtr activationParams,
            [MarshalAs(UnmanagedType.IUnknown)] out object obj);
    }

    [Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioClient {
        [PreserveSig] int Initialize(int shareMode, uint flags, long bufferDuration,
            long periodicity, IntPtr pFormat, IntPtr sessionGuid);
        [PreserveSig] int GetBufferSize(out uint bufferFrames);
        [PreserveSig] int GetStreamLatency(out long latency);
        [PreserveSig] int GetCurrentPadding(out uint padding);
        [PreserveSig] int IsFormatSupported(int shareMode, IntPtr pFormat, out IntPtr closest);
        [PreserveSig] int GetMixFormat(out IntPtr pFormat);
        [PreserveSig] int GetDevicePeriod(out long defaultPeriod, out long minPeriod);
        [PreserveSig] int Start();
        [PreserveSig] int Stop();
        [PreserveSig] int Reset();
        [PreserveSig] int SetEventHandle(IntPtr eventHandle);
        [PreserveSig] int GetService(ref Guid riid,
            [MarshalAs(UnmanagedType.IUnknown)] out object obj);
    }

    [Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioCaptureClient {
        [PreserveSig] int GetBuffer(out IntPtr data, out uint frames, out uint flags,
            out long devicePos, out long qpcPos);
        [PreserveSig] int ReleaseBuffer(uint frames);
        [PreserveSig] int GetNextPacketSize(out uint frames);
    }

    [StructLayout(LayoutKind.Sequential)]
    struct WAVEFORMATEX {
        public ushort wFormatTag, nChannels;
        public uint nSamplesPerSec, nAvgBytesPerSec;
        public ushort nBlockAlign, wBitsPerSample, cbSize;
    }

    [DllImport("ole32.dll")]
    static extern void CoTaskMemFree(IntPtr p);

    static void Check(int hr, string fn) {
        if (hr != 0) throw new Exception(fn + " failed: 0x" + hr.ToString("X8"));
    }

    static int Main(string[] args) {
        if (args.Length < 2) {
            Console.Error.WriteLine("Usage: wasapi_capture <seconds> <output.wav>");
            return 1;
        }
        int secs = int.Parse(args[0]);
        string outPath = args[1];

        try {
            // Get default audio render (speaker) device
            var enumerator = (IMMDeviceEnumerator)(new MMDeviceEnumerator());
            IMMDevice dev;
            Check(enumerator.GetDefaultAudioEndpoint(0 /*eRender*/, 0 /*eConsole*/, out dev),
                "GetDefaultAudioEndpoint");

            // Activate IAudioClient on the render device
            Guid iid = typeof(IAudioClient).GUID;
            object o;
            Check(dev.Activate(ref iid, 1, IntPtr.Zero, out o), "Activate");
            var ac = (IAudioClient)o;

            // Get the device mix format (typically 48kHz float stereo)
            IntPtr pFmt;
            Check(ac.GetMixFormat(out pFmt), "GetMixFormat");
            var wfx = Marshal.PtrToStructure<WAVEFORMATEX>(pFmt);
            int fmtBytes = 18 + wfx.cbSize;
            byte[] fmt = new byte[fmtBytes];
            Marshal.Copy(pFmt, fmt, 0, fmtBytes);

            // Initialize in LOOPBACK mode — captures speaker output
            Check(ac.Initialize(0 /*Shared*/, 0x00020000 /*LOOPBACK*/,
                10000000 /*1s buffer*/, 0, pFmt, IntPtr.Zero), "Initialize");
            CoTaskMemFree(pFmt);

            // Get capture client
            Guid capId = typeof(IAudioCaptureClient).GUID;
            object co;
            Check(ac.GetService(ref capId, out co), "GetService");
            var cc = (IAudioCaptureClient)co;

            // Capture loop
            Check(ac.Start(), "Start");
            using (var ms = new MemoryStream()) {
                var end = DateTime.Now.AddSeconds(secs);
                while (DateTime.Now < end) {
                    uint pkt;
                    cc.GetNextPacketSize(out pkt);
                    while (pkt > 0) {
                        IntPtr d; uint n, f; long dp, qp;
                        if (cc.GetBuffer(out d, out n, out f, out dp, out qp) != 0) break;
                        int b = (int)(n * wfx.nBlockAlign);
                        byte[] buf = new byte[b];
                        if ((f & 2) == 0) Marshal.Copy(d, buf, 0, b);
                        ms.Write(buf, 0, b);
                        cc.ReleaseBuffer(n);
                        cc.GetNextPacketSize(out pkt);
                    }
                    Thread.Sleep(10);
                }
                ac.Stop();

                // Write WAV file (native format — ffmpeg converts to 16kHz mono later)
                byte[] pcm = ms.ToArray();
                using (var fs = new FileStream(outPath, FileMode.Create))
                using (var bw = new BinaryWriter(fs)) {
                    bw.Write(0x46464952); // "RIFF"
                    bw.Write(20 + fmtBytes + pcm.Length);
                    bw.Write(0x45564157); // "WAVE"
                    bw.Write(0x20746D66); // "fmt "
                    bw.Write(fmtBytes);
                    bw.Write(fmt);
                    bw.Write(0x61746164); // "data"
                    bw.Write(pcm.Length);
                    bw.Write(pcm);
                }
            }

            Console.WriteLine("OK " + wfx.nSamplesPerSec + "Hz " + wfx.nChannels + "ch "
                + wfx.wBitsPerSample + "bit");
            return 0;
        } catch (Exception ex) {
            Console.Error.WriteLine(ex.Message);
            return 9;
        }
    }
}
""";
}
