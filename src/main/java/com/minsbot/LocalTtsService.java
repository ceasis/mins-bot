package com.minsbot;

import com.minsbot.firstrun.PiperInstallerService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Offline TTS via the Piper binary. Spawns {@code piper.exe --model <voice>.onnx --output_raw},
 * writes the text to stdin, reads raw PCM (s16le, mono, voice-specific sample rate) from
 * stdout, and wraps it in a WAV header.
 *
 * <p>Fully offline once installed. Zero cloud dependency — this is what makes "offline mode"
 * actually sound good, instead of Windows SAPI's robotic fallback.</p>
 */
@Service
public class LocalTtsService {

    private static final Logger log = LoggerFactory.getLogger(LocalTtsService.class);
    /** Tiny plain-text file holding the last-selected voice filename. */
    private static final Path PREF_FILE =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "piper", ".selected-voice").toAbsolutePath();
    /** Tiny plain-text file holding pitch shift in semitones (0 = unchanged, -2 = JARVIS-ish). */
    private static final Path PITCH_FILE =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "piper", ".pitch-semitones").toAbsolutePath();
    /** Tiny plain-text file holding Piper --length-scale (speech rate). 1.0 = default, >1 = slower. */
    private static final Path LENGTH_SCALE_FILE =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "piper", ".length-scale").toAbsolutePath();
    /** Length-scale used specifically for narration (stories, recitations, bedtime reads). Defaults to 1.35. */
    private static final Path NARRATION_SCALE_FILE =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "piper", ".narration-length-scale").toAbsolutePath();

    private final PiperInstallerService installer;

    private volatile String selectedVoice;
    /** Pitch shift in semitones. Negative = lower. Implemented by modifying WAV
     *  header sample rate, which lowers pitch AND slows playback together —
     *  the slowing is a feature for JARVIS-style measured cadence. */
    private volatile double pitchSemitones = 0.0;
    /** Piper speech rate for normal replies. 1.0 = native; >1 = slower; <1 = faster. */
    private volatile double lengthScale = 1.0;
    /** Piper speech rate used by narration mode (stories, recitations). Default slower. */
    private volatile double narrationLengthScale = 1.35;

    public LocalTtsService(PiperInstallerService installer) {
        this.installer = installer;
    }

    @PostConstruct
    void init() {
        // Restore last-selected voice; fall back to first installed voice if any.
        try {
            if (Files.isRegularFile(PREF_FILE)) {
                String saved = Files.readString(PREF_FILE).trim();
                if (!saved.isBlank() && installer.hasVoice(saved)) {
                    selectedVoice = saved;
                }
            }
        } catch (Exception ignored) {}
        // Restore pitch
        try {
            if (Files.isRegularFile(PITCH_FILE)) {
                String saved = Files.readString(PITCH_FILE).trim();
                if (!saved.isBlank()) pitchSemitones = clampPitch(Double.parseDouble(saved));
            }
        } catch (Exception ignored) {}
        // Restore length-scale
        try {
            if (Files.isRegularFile(LENGTH_SCALE_FILE)) {
                String saved = Files.readString(LENGTH_SCALE_FILE).trim();
                if (!saved.isBlank()) lengthScale = clampLengthScale(Double.parseDouble(saved));
            }
        } catch (Exception ignored) {}
        // Restore narration length-scale
        try {
            if (Files.isRegularFile(NARRATION_SCALE_FILE)) {
                String saved = Files.readString(NARRATION_SCALE_FILE).trim();
                if (!saved.isBlank()) narrationLengthScale = clampLengthScale(Double.parseDouble(saved));
            }
        } catch (Exception ignored) {}
        if (selectedVoice == null) selectedVoice = firstInstalledVoice();
        if (selectedVoice != null) {
            log.info("[LocalTTS] Ready — binary={}, voice={}, pitchSemitones={}, lengthScale={}, narrationLengthScale={}",
                    installer.binary(), selectedVoice, pitchSemitones, lengthScale, narrationLengthScale);
        } else if (installer.isInstalled()) {
            log.info("[LocalTTS] Binary installed but no voices yet — install one from the Models tab.");
        }
    }

    public double getNarrationLengthScale() { return narrationLengthScale; }

    public synchronized void setNarrationLengthScale(double scale) {
        this.narrationLengthScale = clampLengthScale(scale);
        try {
            Files.createDirectories(NARRATION_SCALE_FILE.getParent());
            Files.writeString(NARRATION_SCALE_FILE, String.valueOf(this.narrationLengthScale));
        } catch (Exception e) {
            log.warn("[LocalTTS] couldn't persist narration length-scale pref: {}", e.getMessage());
        }
        log.info("[LocalTTS] Narration length-scale set to {}", this.narrationLengthScale);
    }

    public double getPitchSemitones() { return pitchSemitones; }

    public synchronized void setPitchSemitones(double semitones) {
        this.pitchSemitones = clampPitch(semitones);
        try {
            Files.createDirectories(PITCH_FILE.getParent());
            Files.writeString(PITCH_FILE, String.valueOf(this.pitchSemitones));
        } catch (Exception e) {
            log.warn("[LocalTTS] couldn't persist pitch pref: {}", e.getMessage());
        }
        log.info("[LocalTTS] Pitch set to {} semitones", this.pitchSemitones);
    }

    public double getLengthScale() { return lengthScale; }

    public synchronized void setLengthScale(double scale) {
        this.lengthScale = clampLengthScale(scale);
        try {
            Files.createDirectories(LENGTH_SCALE_FILE.getParent());
            Files.writeString(LENGTH_SCALE_FILE, String.valueOf(this.lengthScale));
        } catch (Exception e) {
            log.warn("[LocalTTS] couldn't persist length-scale pref: {}", e.getMessage());
        }
        log.info("[LocalTTS] Length-scale set to {}", this.lengthScale);
    }

    private static double clampLengthScale(double v) { return Math.max(0.5, Math.min(3.0, v)); }

    private static double clampPitch(double s) {
        return Math.max(-12.0, Math.min(12.0, s));
    }

    public boolean isEnabled() {
        return installer.isReady() && selectedVoice != null && installer.hasVoice(selectedVoice);
    }

    /** Voice filename (e.g. {@code en_US-amy-medium.onnx}). */
    public String getSelectedVoice() { return selectedVoice; }

    public List<String> listInstalledVoices() {
        Path dir = installer.voices();
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".onnx"))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Pick a voice by filename. Persists to disk so it survives restarts. */
    public synchronized boolean setSelectedVoice(String filename) {
        if (filename == null || !installer.hasVoice(filename)) return false;
        this.selectedVoice = filename;
        try {
            Files.createDirectories(PREF_FILE.getParent());
            Files.writeString(PREF_FILE, filename);
        } catch (Exception e) {
            log.warn("[LocalTTS] couldn't persist voice pref: {}", e.getMessage());
        }
        log.info("[LocalTTS] Voice set to {}", filename);
        return true;
    }

    /**
     * Synthesize text → WAV bytes. Returns null on failure so callers can fall through to
     * the next TTS backend in their chain.
     */
    public byte[] textToSpeech(String text) {
        return textToSpeech(text, null);
    }

    /** Synthesize with a one-off length-scale override (e.g. narration uses a slower rate
     *  than the persisted default). null = use the persisted lengthScale. */
    public byte[] textToSpeech(String text, Double lengthScaleOverride) {
        if (!isEnabled() || text == null || text.isBlank()) return null;
        Path model = installer.voices().resolve(selectedVoice);
        int sampleRate = readSampleRate(model, 22050);
        // Apply pitch shift via sample-rate tweak: rate * 2^(semitones/12).
        // Negative semitones lower the rate → players play samples slower & at lower pitch.
        // For JARVIS the slower cadence is desired, so coupled pitch+tempo is a feature.
        if (pitchSemitones != 0.0) {
            sampleRate = (int) Math.round(sampleRate * Math.pow(2, pitchSemitones / 12.0));
        }
        double effectiveScale = lengthScaleOverride != null ? clampLengthScale(lengthScaleOverride) : lengthScale;

        try {
            java.util.List<String> args = new java.util.ArrayList<>(java.util.List.of(
                    installer.binary().toString(),
                    "--model", model.toString(),
                    "--output_raw"));
            if (effectiveScale != 1.0) {
                args.add("--length-scale");
                args.add(String.valueOf(effectiveScale));
            }
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(false);
            Process p = pb.start();

            // Feed text via stdin, then close so piper knows input is done.
            p.getOutputStream().write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            p.getOutputStream().flush();
            p.getOutputStream().close();

            ByteArrayOutputStream pcmBuf = new ByteArrayOutputStream(64 * 1024);
            try (InputStream in = p.getInputStream()) {
                byte[] chunk = new byte[16 * 1024];
                int n;
                while ((n = in.read(chunk)) > 0) pcmBuf.write(chunk, 0, n);
            }

            boolean done = p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); log.warn("[LocalTTS] piper timed out"); return null; }

            byte[] pcm = pcmBuf.toByteArray();
            if (pcm.length < 256) {
                // Likely an error — drain stderr for diagnostics.
                String err = new String(p.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                log.warn("[LocalTTS] piper produced {} PCM bytes; stderr: {}", pcm.length,
                        err.length() > 300 ? err.substring(0, 300) : err);
                return null;
            }
            return wrapPcmAsWav(pcm, sampleRate);
        } catch (Exception e) {
            log.warn("[LocalTTS] synth failed: {}", e.getMessage(), e);
            return null;
        }
    }

    /** Streaming variant — same WAV bytes, served back as an InputStream. */
    public InputStream textToSpeechStream(String text) {
        byte[] wav = textToSpeech(text);
        return wav == null ? null : new ByteArrayInputStream(wav);
    }

    /** Streaming variant with a one-off length-scale override. */
    public InputStream textToSpeechStream(String text, Double lengthScaleOverride) {
        byte[] wav = textToSpeech(text, lengthScaleOverride);
        return wav == null ? null : new ByteArrayInputStream(wav);
    }

    // ─── helpers ═════════════════════════════════════════════════════

    private String firstInstalledVoice() {
        List<String> list = listInstalledVoices();
        if (list.isEmpty()) return null;
        // Prefer "amy-medium" as a sensible default if present.
        return list.stream()
                .min(Comparator.<String, Integer>comparing(name -> name.contains("amy-medium") ? 0 : 1)
                        .thenComparing(Comparator.naturalOrder()))
                .orElse(null);
    }

    /** Parse {@code audio.sample_rate} from the {@code .onnx.json} config. Falls back to default. */
    private static int readSampleRate(Path onnxPath, int fallback) {
        Path json = onnxPath.resolveSibling(onnxPath.getFileName().toString() + ".json");
        if (!Files.isRegularFile(json)) return fallback;
        try {
            String body = Files.readString(json);
            // Match: "sample_rate": 22050  — lenient regex, avoid a JSON parser dep
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"sample_rate\"\\s*:\\s*(\\d+)").matcher(body);
            if (m.find()) return Integer.parseInt(m.group(1));
        } catch (Exception ignored) {}
        return fallback;
    }

    /** Wrap raw s16le mono PCM with a 44-byte WAV header. */
    private static byte[] wrapPcmAsWav(byte[] pcm, int sampleRate) {
        int channels = 1, bitsPerSample = 16;
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int dataLen = pcm.length;
        int chunkSize = 36 + dataLen;

        byte[] out = new byte[44 + dataLen];
        // RIFF header
        out[0] = 'R'; out[1] = 'I'; out[2] = 'F'; out[3] = 'F';
        writeLE(out, 4, chunkSize, 4);
        out[8] = 'W'; out[9] = 'A'; out[10] = 'V'; out[11] = 'E';
        // fmt chunk
        out[12] = 'f'; out[13] = 'm'; out[14] = 't'; out[15] = ' ';
        writeLE(out, 16, 16, 4);       // fmt chunk size
        writeLE(out, 20, 1, 2);        // PCM format
        writeLE(out, 22, channels, 2);
        writeLE(out, 24, sampleRate, 4);
        writeLE(out, 28, byteRate, 4);
        writeLE(out, 32, blockAlign, 2);
        writeLE(out, 34, bitsPerSample, 2);
        // data chunk
        out[36] = 'd'; out[37] = 'a'; out[38] = 't'; out[39] = 'a';
        writeLE(out, 40, dataLen, 4);
        System.arraycopy(pcm, 0, out, 44, dataLen);
        return out;
    }

    private static void writeLE(byte[] out, int offset, int value, int bytes) {
        for (int i = 0; i < bytes; i++) out[offset + i] = (byte) ((value >>> (i * 8)) & 0xff);
    }
}
