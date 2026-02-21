package com.minsbot.agent;

import com.minsbot.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import javax.sound.sampled.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Periodically captures system audio (speaker output) via loopback/Stereo Mix,
 * records WAV clips, transcribes via OpenAI Whisper, and stores timestamped text.
 *
 * Storage: ~/mins_bot_data/audio_memory/YYYY-MM-DD.txt
 * Clips:   ~/mins_bot_data/audio_memory/clips/ (when keep_wav = true)
 * Config:  ~/mins_bot_data/minsbot_config.txt section "## Audio memory"
 *
 * Requires "Stereo Mix" enabled in Windows Sound settings.
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

    // Audio format: Whisper-compatible (matches NativeVoiceService)
    private static final float SAMPLE_RATE = 16000f;
    private static final int SAMPLE_SIZE_BITS = 16;
    private static final int CHANNELS = 1;

    // Config (mutable, reloaded at runtime)
    private volatile boolean enabled = false;
    private volatile int intervalSeconds = 60;
    private volatile int clipSeconds = 15;
    private volatile boolean keepWav = false;

    private volatile String lastText = "";
    private volatile long lastRunMs = 0;

    /** Cached loopback mixer info (found at init, null if none). */
    private volatile Mixer.Info loopbackMixer = null;

    private final ChatService chatService;

    public AudioMemoryService(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(AUDIO_MEMORY_DIR);
        loadConfigFromFile();
        loopbackMixer = findLoopbackMixer();
        if (loopbackMixer != null) {
            log.info("[AudioMemory] Found loopback mixer: {}", loopbackMixer.getName());
        } else {
            log.warn("[AudioMemory] No loopback mixer found. Enable 'Stereo Mix' in Windows Sound settings.");
        }
    }

    /** Re-read config. Called by ConfigScanService when minsbot_config.txt changes. */
    public void reloadConfig() {
        loadConfigFromFile();
        // Re-detect mixer in case user enabled Stereo Mix
        Mixer.Info newMixer = findLoopbackMixer();
        if (newMixer != null && loopbackMixer == null) {
            log.info("[AudioMemory] Loopback mixer now available: {}", newMixer.getName());
        }
        loopbackMixer = newMixer;
        log.info("[AudioMemory] Config reloaded — enabled={}, interval={}s, clip={}s, keepWav={}",
                enabled, intervalSeconds, clipSeconds, keepWav);
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

        saveWavIfEnabled(wav);

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

        saveWavIfEnabled(wav);

        String text = transcribe(wav);
        if (text == null || text.isBlank()) return;

        String collapsed = collapse(text);

        // Dedup
        if (collapsed.equals(lastText)) return;
        lastText = collapsed;

        appendToDaily(collapsed);
    }

    /**
     * Record clip_seconds of system audio from the loopback mixer.
     * Returns WAV bytes, or null if no mixer / silence / error.
     */
    private byte[] captureSystemAudio() {
        if (loopbackMixer == null) {
            log.debug("[AudioMemory] No loopback mixer available");
            return null;
        }

        AudioFormat format = new AudioFormat(SAMPLE_RATE, SAMPLE_SIZE_BITS, CHANNELS, true, false);
        DataLine.Info lineInfo = new DataLine.Info(TargetDataLine.class, format);

        try {
            Mixer mixer = AudioSystem.getMixer(loopbackMixer);
            TargetDataLine line = (TargetDataLine) mixer.getLine(lineInfo);
            line.open(format);
            line.start();

            long endAt = System.currentTimeMillis() + (clipSeconds * 1000L);
            ByteArrayOutputStream pcmOut = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];

            while (System.currentTimeMillis() < endAt) {
                int read = line.read(buffer, 0, buffer.length);
                if (read > 0) pcmOut.write(buffer, 0, read);
            }

            line.stop();
            line.close();

            byte[] pcm = pcmOut.toByteArray();
            if (pcm.length == 0 || isSilent(pcm)) {
                log.debug("[AudioMemory] Captured silence, skipping");
                return null;
            }

            // Convert PCM to WAV
            ByteArrayInputStream bais = new ByteArrayInputStream(pcm);
            long frameLength = pcm.length / format.getFrameSize();
            AudioInputStream ais = new AudioInputStream(bais, format, frameLength);
            ByteArrayOutputStream wavOut = new ByteArrayOutputStream();
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, wavOut);
            return wavOut.toByteArray();

        } catch (Exception e) {
            log.warn("[AudioMemory] Audio capture failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Enumerate audio mixers and find one that looks like a loopback/Stereo Mix device.
     */
    private Mixer.Info findLoopbackMixer() {
        if (!isWindows()) return null;

        AudioFormat format = new AudioFormat(SAMPLE_RATE, SAMPLE_SIZE_BITS, CHANNELS, true, false);
        DataLine.Info lineInfo = new DataLine.Info(TargetDataLine.class, format);

        String[] patterns = {
                "stereo mix", "loopback", "what u hear", "wave out mix",
                "monitor of", "rec. playback", "what you hear"
        };

        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            String name = mi.getName().toLowerCase();
            for (String pat : patterns) {
                if (name.contains(pat)) {
                    try {
                        Mixer mixer = AudioSystem.getMixer(mi);
                        if (mixer.isLineSupported(lineInfo)) {
                            return mi;
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        return null;
    }

    /**
     * Check if PCM data is effectively silence (all samples below threshold).
     */
    private boolean isSilent(byte[] pcm) {
        int threshold = 200;
        for (int i = 0; i < pcm.length - 1; i += 2) {
            short sample = (short) ((pcm[i + 1] << 8) | (pcm[i] & 0xFF));
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

    private void saveWavIfEnabled(byte[] wav) {
        if (!keepWav) return;
        try {
            Files.createDirectories(CLIPS_DIR);
            String filename = LocalDateTime.now().format(FILE_TIME_FMT) + ".wav";
            Files.write(CLIPS_DIR.resolve(filename), wav);
        } catch (IOException e) {
            log.warn("[AudioMemory] Failed to save WAV clip: {}", e.getMessage());
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
                    case "keep_wav" -> keepWav = val.equals("true");
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
