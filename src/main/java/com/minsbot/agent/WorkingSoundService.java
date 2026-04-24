package com.minsbot.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumMap;
import java.util.Map;

/**
 * Context-aware sound service that changes its audio signature based on
 * what the bot is currently doing. Each tool category produces a distinct
 * synthesized sound so the user can tell browsing from file ops from
 * computation by ear alone.
 *
 * <p>Phases:
 * <ul>
 *   <li><b>THINKING</b> — soft ambient pulse (AI reasoning, no tool yet)</li>
 *   <li><b>BROWSING</b> — digital data-transfer tones (web/search)</li>
 *   <li><b>FILE_OPS</b> — rhythmic mechanical clicks (file/system ops)</li>
 *   <li><b>NETWORK</b>  — carrier stream with modulation (download/email/API)</li>
 *   <li><b>COMPUTING</b> — rapid processing beeps (math/hash/convert)</li>
 * </ul>
 */
@Component
public class WorkingSoundService {

    private static final Logger log = LoggerFactory.getLogger(WorkingSoundService.class);

    public enum SoundPhase {
        THINKING, BROWSING, FILE_OPS, NETWORK, COMPUTING
    }

    private static final float SAMPLE_RATE = 22050f;

    private static final Path CONFIG_PATH =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "minsbot_config.txt");

    // Defaults — overridden by minsbot_config.txt on startup
    private volatile boolean soundEnabled = true;
    private volatile float volume = 0.01f;
    private volatile long minSwitchMs = 1500;

    public boolean isSoundEnabled() { return soundEnabled; }
    public float getVolume() { return volume; }
    public synchronized void setSoundEnabled(boolean v) {
        this.soundEnabled = v;
        persistPrefs();
        if (!v && playing && clip != null) { try { clip.stop(); } catch (Exception ignored) {} }
    }
    public synchronized void setVolume(float v) {
        this.volume = Math.max(0f, Math.min(1f, v));
        persistPrefs();
        if (clip != null) { try { setVolume(clip); } catch (Exception ignored) {} }
    }
    private static final java.nio.file.Path SOUND_PREFS_PATH =
            java.nio.file.Paths.get(System.getProperty("user.home"), "mins_bot_data", "sound_prefs.txt");

    private void persistPrefs() {
        // Write a sidecar file rather than touching minsbot_config.txt (which may hold
        // other sections). loadConfigFromFile() continues to read the shared config;
        // we layer our runtime overrides after that.
        try {
            java.nio.file.Files.createDirectories(SOUND_PREFS_PATH.getParent());
            String body = "enabled=" + soundEnabled + "\nvolume=" + volume + "\n";
            java.nio.file.Files.writeString(SOUND_PREFS_PATH, body);
        } catch (Exception ignored) {}
    }

    @jakarta.annotation.PostConstruct
    void loadSoundPrefs() {
        try {
            if (!java.nio.file.Files.exists(SOUND_PREFS_PATH)) return;
            for (String line : java.nio.file.Files.readAllLines(SOUND_PREFS_PATH)) {
                line = line.trim();
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                try {
                    switch (k) {
                        case "enabled" -> this.soundEnabled = Boolean.parseBoolean(v);
                        case "volume" -> this.volume = Float.parseFloat(v);
                    }
                } catch (NumberFormatException ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private volatile Clip clip;
    private volatile boolean playing = false;
    private volatile SoundPhase currentPhase = null;
    private volatile long lastSwitchTime = 0;

    private final Map<SoundPhase, byte[]> soundCache = new EnumMap<>(SoundPhase.class);

    @PostConstruct
    public void init() {
        loadConfigFromFile();
    }

    /** Re-read minsbot_config.txt. Called by ConfigScanService when file changes. */
    public void reloadConfig() {
        boolean wasEnabled = soundEnabled;
        loadConfigFromFile();
        // If sound was just disabled while playing, stop immediately
        if (wasEnabled && !soundEnabled && playing) {
            stop();
        }
    }

    // ═══ Public API ═══

    /** Start with the THINKING phase. Safe to call multiple times. */
    public synchronized void start() {
        if (!soundEnabled || playing) return;
        playing = true;
        currentPhase = null;
        playPhase(SoundPhase.THINKING);
    }

    /** Stop all sound. Safe to call even if not playing. */
    public synchronized void stop() {
        playing = false;
        stopClip();
        currentPhase = null;
    }

    public boolean isPlaying() {
        return playing;
    }

    /**
     * Called by ToolExecutionNotifier when a tool reports its status.
     * Detects the appropriate phase from the message and switches sound.
     * Stops sound when the bot is speaking (TTS) so the dial-up sound doesn't play over the voice.
     */
    public void onToolExecution(String message) {
        if (!playing || message == null) return;
        if (isTtsOrSpeaking(message)) {
            stop();
            return;
        }
        SoundPhase phase = detectPhase(message);
        if (phase == currentPhase) return;
        long now = System.currentTimeMillis();
        if (now - lastSwitchTime < minSwitchMs) return;
        synchronized (this) {
            if (!playing || phase == currentPhase) return;
            playPhase(phase);
        }
    }

    // ═══ Phase detection ═══

    /** True when the notification is from TTS/speak — we stop working sound so it doesn't play over the bot's voice. */
    private static boolean isTtsOrSpeaking(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();
        return lower.startsWith("speaking") || containsAny(lower, "speak", "read aloud", "tts", "text-to-speech");
    }

    private static SoundPhase detectPhase(String msg) {
        String lower = msg.toLowerCase();

        // BROWSING — web / search / page navigation
        if (containsAny(lower, "browsing", "searching", "google", "youtube",
                "fetching page", "scanning image", "scanning link",
                "screenshotting", "reading browser", "collecting images from browser",
                "downloading images from browser", "clicking on", "filling form",
                "searching image")) {
            return SoundPhase.BROWSING;
        }

        // NETWORK — downloads, email, weather, API calls, model pulling
        if (containsAny(lower, "downloading", "download", "email", "inbox",
                "weather", "pulling model", "pulling ", "installing",
                "pinging", "fetching", "hugging face")) {
            return SoundPhase.NETWORK;
        }

        // COMPUTING — math, hash, QR, conversion
        if (containsAny(lower, "calculating", "computing", "converting",
                "qr code", "decoding qr", "generating qr",
                "hash", "sha-", "md5", "summariz", "classif")) {
            return SoundPhase.COMPUTING;
        }

        // FILE_OPS — file operations, system commands, screenshots
        if (containsAny(lower, "listing", "counting", "creating directory",
                "writing file", "reading", "copying", "moving", "renaming",
                "deleting", "opening", "zipping", "unzipping", "collecting",
                "searching files", "exporting", "powershell", "cmd:",
                "screenshot", "closing", "minimizing", "locking",
                "shutting down", "sleep", "hibernate", "muting", "unmuting",
                "focusing", "keystroke", "wallpaper", "system info",
                "saving", "checking")) {
            return SoundPhase.FILE_OPS;
        }

        return SoundPhase.THINKING;
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    // ═══ Playback ═══

    private void playPhase(SoundPhase phase) {
        stopClip();
        currentPhase = phase;
        lastSwitchTime = System.currentTimeMillis();

        byte[] wav = soundCache.computeIfAbsent(phase, p -> {
            try {
                return generateSound(p);
            } catch (IOException e) {
                log.error("Failed to generate sound for {}", p, e);
                return new byte[0];
            }
        });

        if (wav.length == 0) return;

        try {
            clip = AudioSystem.getClip();
            AudioInputStream ais = AudioSystem.getAudioInputStream(
                    new ByteArrayInputStream(wav));
            clip.open(ais);
            setVolume(clip);
            clip.loop(Clip.LOOP_CONTINUOUSLY);
            log.debug("[Sound] Switched to phase: {}", phase);
        } catch (Exception e) {
            log.debug("Could not play phase {}: {}", phase, e.getMessage());
        }
    }

    private void setVolume(Clip c) {
        if (c.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            FloatControl gain = (FloatControl) c.getControl(FloatControl.Type.MASTER_GAIN);
            float dB = 20f * (float) Math.log10(Math.max(volume, 0.001f));
            dB = Math.max(dB, gain.getMinimum());
            dB = Math.min(dB, gain.getMaximum());
            gain.setValue(dB);
        }
    }

    private void stopClip() {
        if (clip != null) {
            try {
                clip.stop();
                clip.close();
            } catch (Exception ignored) {
            }
            clip = null;
        }
    }

    // ═══ Sound generation ═══

    private byte[] generateSound(SoundPhase phase) throws IOException {
        return switch (phase) {
            case THINKING  -> generateThinking();
            case BROWSING  -> generateBrowsing();
            case FILE_OPS  -> generateFileOps();
            case NETWORK   -> generateNetwork();
            case COMPUTING -> generateComputing();
        };
    }

    /**
     * THINKING: Soft ambient pulse — low frequency with gentle breathing amplitude.
     * Conveys "the AI is reasoning quietly."
     */
    private byte[] generateThinking() throws IOException {
        int samples = (int) (SAMPLE_RATE * 3);
        byte[] pcm = new byte[samples];
        for (int i = 0; i < samples; i++) {
            double t = i / (double) SAMPLE_RATE;
            // Deep base tone with slow amplitude modulation
            double breath = 0.5 + 0.5 * Math.sin(2 * Math.PI * 0.4 * t); // slow pulse
            double base = Math.sin(2 * Math.PI * 90 * t);                 // 90Hz hum
            double overtone = 0.3 * Math.sin(2 * Math.PI * 135 * t);      // soft fifth
            double shimmer = 0.1 * Math.sin(2 * Math.PI * 270 * t)        // gentle shimmer
                           * Math.sin(2 * Math.PI * 1.7 * t);
            double sample = (base + overtone + shimmer) * breath * 0.4;
            pcm[i] = clamp(sample * envelope(t, 3));
        }
        return wrapWav(pcm);
    }

    /**
     * BROWSING: Digital data-transfer tones — sweeping mid frequencies with
     * rapid modulation, like data flowing through wires.
     */
    private byte[] generateBrowsing() throws IOException {
        int samples = (int) (SAMPLE_RATE * 3);
        byte[] pcm = new byte[samples];
        for (int i = 0; i < samples; i++) {
            double t = i / (double) SAMPLE_RATE;
            // Sweeping carrier frequency (800-1800Hz)
            double sweep = 1300 + 500 * Math.sin(2 * Math.PI * 2.3 * t);
            double carrier = Math.sin(2 * Math.PI * sweep * t);
            // Rapid data-pulse modulation
            double dataPulse = 0.6 + 0.4 * Math.sin(2 * Math.PI * 8 * t);
            // Secondary higher harmonic for digital feel
            double digital = 0.25 * Math.sin(2 * Math.PI * (sweep * 1.5) * t);
            // Very subtle noise floor
            double noise = 0.04 * (Math.random() * 2 - 1);
            double sample = (carrier * dataPulse + digital + noise) * 0.35;
            pcm[i] = clamp(sample * envelope(t, 3));
        }
        return wrapWav(pcm);
    }

    /**
     * FILE_OPS: Rhythmic mechanical pattern — evokes hard drive seeking,
     * periodic short click-like bursts over a low hum.
     */
    private byte[] generateFileOps() throws IOException {
        int samples = (int) (SAMPLE_RATE * 3);
        byte[] pcm = new byte[samples];
        for (int i = 0; i < samples; i++) {
            double t = i / (double) SAMPLE_RATE;
            // Low mechanical hum
            double hum = 0.3 * Math.sin(2 * Math.PI * 150 * t);
            // Rhythmic "click" pattern (6 clicks per second)
            double clickEnv = Math.pow(Math.max(0, Math.sin(2 * Math.PI * 6 * t)), 8);
            double click = clickEnv * Math.sin(2 * Math.PI * 400 * t);
            // Servo-whirr: frequency that steps in discrete jumps
            double step = Math.floor(t * 4) / 4.0; // step every 250ms
            double servo = 0.15 * Math.sin(2 * Math.PI * (250 + step * 100) * t);
            double sample = (hum + click * 0.5 + servo) * 0.4;
            pcm[i] = clamp(sample * envelope(t, 3));
        }
        return wrapWav(pcm);
    }

    /**
     * NETWORK: Data stream with modulated carrier — like a cleaner modem
     * carrier, steady tone with rhythmic amplitude shifts.
     */
    private byte[] generateNetwork() throws IOException {
        int samples = (int) (SAMPLE_RATE * 3);
        byte[] pcm = new byte[samples];
        for (int i = 0; i < samples; i++) {
            double t = i / (double) SAMPLE_RATE;
            // Carrier tone
            double carrier = Math.sin(2 * Math.PI * 1400 * t);
            // Amplitude-shift keying (simulates data packets)
            double ask = 0.7 + 0.3 * Math.signum(Math.sin(2 * Math.PI * 4.5 * t));
            // Secondary sideband
            double sideband = 0.2 * Math.sin(2 * Math.PI * 1600 * t);
            // Slow drift
            double drift = 0.1 * Math.sin(2 * Math.PI * 0.8 * t)
                         * Math.sin(2 * Math.PI * 1200 * t);
            double sample = (carrier * ask + sideband + drift) * 0.3;
            pcm[i] = clamp(sample * envelope(t, 3));
        }
        return wrapWav(pcm);
    }

    /**
     * COMPUTING: Quick rhythmic high-pitched beeps — like a retro CPU
     * crunching numbers, precise and mathematical.
     */
    private byte[] generateComputing() throws IOException {
        int samples = (int) (SAMPLE_RATE * 3);
        byte[] pcm = new byte[samples];
        for (int i = 0; i < samples; i++) {
            double t = i / (double) SAMPLE_RATE;
            // Rapid beep train (12 beeps/sec with short duty cycle)
            double beepPhase = (t * 12) % 1.0;
            double beepEnv = beepPhase < 0.3 ? 1.0 : 0.0; // 30% duty cycle
            double beep = beepEnv * Math.sin(2 * Math.PI * 2200 * t);
            // Lower counter-rhythm (slower, complementary)
            double counterPhase = (t * 3) % 1.0;
            double counterEnv = counterPhase < 0.5 ? 0.3 : 0.0;
            double counter = counterEnv * Math.sin(2 * Math.PI * 880 * t);
            // Tiny bit of computational "chatter"
            double chatter = 0.08 * Math.sin(2 * Math.PI * 3300 * t)
                           * (Math.sin(2 * Math.PI * 17 * t) > 0.5 ? 1 : 0);
            double sample = (beep * 0.35 + counter + chatter) * 0.4;
            pcm[i] = clamp(sample * envelope(t, 3));
        }
        return wrapWav(pcm);
    }

    // ═══ Helpers ═══

    /** Fade in/out envelope for seamless looping. */
    private static double envelope(double t, double duration) {
        double fadeTime = 0.05;
        if (t < fadeTime) return t / fadeTime;
        if (t > duration - fadeTime) return (duration - t) / fadeTime;
        return 1.0;
    }

    private static byte clamp(double sample) {
        sample = Math.max(-1, Math.min(1, sample));
        return (byte) (sample * 127);
    }

    /** Create a minimal WAV file from raw 8-bit signed PCM mono data. */
    private byte[] wrapWav(byte[] pcm) throws IOException {
        int sr = (int) SAMPLE_RATE;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();

        bos.write("RIFF".getBytes());
        writeInt(bos, 36 + pcm.length);
        bos.write("WAVE".getBytes());

        bos.write("fmt ".getBytes());
        writeInt(bos, 16);
        writeShort(bos, 1);   // PCM
        writeShort(bos, 1);   // mono
        writeInt(bos, sr);
        writeInt(bos, sr);    // byte rate
        writeShort(bos, 1);   // block align
        writeShort(bos, 8);   // bits per sample

        bos.write("data".getBytes());
        writeInt(bos, pcm.length);
        bos.write(pcm);

        return bos.toByteArray();
    }

    private void writeInt(ByteArrayOutputStream bos, int v) {
        bos.write(v & 0xFF);
        bos.write((v >> 8) & 0xFF);
        bos.write((v >> 16) & 0xFF);
        bos.write((v >> 24) & 0xFF);
    }

    private void writeShort(ByteArrayOutputStream bos, int v) {
        bos.write(v & 0xFF);
        bos.write((v >> 8) & 0xFF);
    }

    // ═══ Config file parsing ═══

    /**
     * Read sound params from ~/mins_bot_data/minsbot_config.txt (## Sound section).
     * Expected lines: "- enabled: true", "- volume: 0.01", "- min_switch_ms: 1500".
     */
    private void loadConfigFromFile() {
        if (!Files.exists(CONFIG_PATH)) return;
        try {
            boolean prevEnabled = soundEnabled;
            float prevVolume = volume;
            long prevSwitch = minSwitchMs;

            String currentSection = "";
            for (String line : Files.readAllLines(CONFIG_PATH)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("## ")) {
                    currentSection = trimmed.toLowerCase();
                    continue;
                }
                if (!trimmed.startsWith("- ")) continue;
                String kv = trimmed.substring(2).trim(); // strip "- "
                int colon = kv.indexOf(':');
                if (colon < 0) continue;
                String key = kv.substring(0, colon).trim().toLowerCase();
                String val = kv.substring(colon + 1).trim().toLowerCase();

                if (currentSection.equals("## sound")) {
                    switch (key) {
                        case "enabled" -> soundEnabled = val.equals("true");
                        case "volume" -> {
                            try { volume = Float.parseFloat(val); } catch (NumberFormatException ignored) {}
                        }
                        case "min_switch_ms" -> {
                            try { minSwitchMs = Long.parseLong(val); } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            }
            // Only log when something changed
            if (prevEnabled != soundEnabled || prevVolume != volume || prevSwitch != minSwitchMs) {
                log.info("[Sound] Config changed — enabled={}, volume={}, minSwitchMs={}", soundEnabled, volume, minSwitchMs);
            }
        } catch (IOException e) {
            log.warn("[Sound] Could not read minsbot_config.txt: {}", e.getMessage());
        }
    }
}
