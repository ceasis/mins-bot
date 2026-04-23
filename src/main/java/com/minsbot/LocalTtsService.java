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

    private final PiperInstallerService installer;

    private volatile String selectedVoice;

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
        if (selectedVoice == null) selectedVoice = firstInstalledVoice();
        if (selectedVoice != null) {
            log.info("[LocalTTS] Ready — binary={}, voice={}", installer.binary(), selectedVoice);
        } else if (installer.isInstalled()) {
            log.info("[LocalTTS] Binary installed but no voices yet — install one from the Models tab.");
        }
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
        if (!isEnabled() || text == null || text.isBlank()) return null;
        Path model = installer.voices().resolve(selectedVoice);
        int sampleRate = readSampleRate(model, 22050);

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    installer.binary().toString(),
                    "--model", model.toString(),
                    "--output_raw");
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
