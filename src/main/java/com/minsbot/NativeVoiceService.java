package com.minsbot;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native mic capture -> LLM audio request.
 * Captures a short WAV clip and asks ChatService for a direct audio reply.
 *
 * <p>Reads mic_device from ~/mins_bot_data/minsbot_config.txt (## Voice section).
 * If set, uses that specific microphone. Otherwise uses the system default.
 * This prevents AirPods/Bluetooth from hijacking the mic when connected.
 */
public class NativeVoiceService {

    private static final int CAPTURE_SECONDS = 8;
    private static final float SAMPLE_RATE = 16000f;
    private static final int SAMPLE_SIZE_BITS = 16;
    private static final int CHANNELS = 1;

    private static final Path CONFIG_PATH =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "minsbot_config.txt");

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "native-voice-worker");
        t.setDaemon(true);
        return t;
    });
    private final ChatService chatService;

    private final Object lock = new Object();
    private volatile TargetDataLine activeLine;
    private volatile boolean listening;
    private volatile boolean stopRequested;
    private volatile String transcriptOrReply;
    private volatile String error;

    /** Configured mic device name (null = system default). */
    private volatile String micDevice;

    public NativeVoiceService(ChatService chatService) {
        this.chatService = chatService;
        loadMicConfig();
    }

    public boolean isAvailable() {
        try {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, SAMPLE_SIZE_BITS, CHANNELS, true, false);
            return AudioSystem.isLineSupported(new DataLine.Info(TargetDataLine.class, format));
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isListening() {
        return listening;
    }

    public boolean start() {
        if (!isAvailable()) {
            error = "Native voice is currently only supported on Windows.";
            return false;
        }
        // Reload config each time so user changes take effect without restart
        loadMicConfig();
        synchronized (lock) {
            if (listening) return false;
            transcriptOrReply = "";
            error = "";
            stopRequested = false;
            listening = true;
            executor.submit(this::runAudioChat);
            return true;
        }
    }

    public void stop() {
        synchronized (lock) {
            stopRequested = true;
            TargetDataLine line = activeLine;
            if (line != null) {
                try {
                    line.stop();
                    line.close();
                } catch (Exception ignored) {
                }
            }
            listening = false;
        }
    }

    public String consumeTranscript() {
        String value = transcriptOrReply;
        transcriptOrReply = "";
        return value == null ? "" : value;
    }

    public String consumeError() {
        String value = error;
        error = "";
        return value == null ? "" : value;
    }

    public void shutdown() {
        stop();
        executor.shutdownNow();
    }

    /**
     * List all available microphone (TargetDataLine) devices.
     * Returns device names that the user can set as mic_device in config.
     */
    public static List<String> listMicrophones() {
        List<String> mics = new ArrayList<>();
        AudioFormat format = new AudioFormat(SAMPLE_RATE, SAMPLE_SIZE_BITS, CHANNELS, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            try {
                Mixer mixer = AudioSystem.getMixer(mixerInfo);
                if (mixer.isLineSupported(info)) {
                    mics.add(mixerInfo.getName());
                }
            } catch (Exception ignored) {}
        }
        return mics;
    }

    // ═══ Config ═══

    private void loadMicConfig() {
        if (!Files.exists(CONFIG_PATH)) return;
        try {
            String currentSection = "";
            for (String line : Files.readAllLines(CONFIG_PATH)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("## ")) {
                    currentSection = trimmed.toLowerCase();
                    continue;
                }
                if (!currentSection.equals("## voice")) continue;
                if (!trimmed.startsWith("- ")) continue;

                String kv = trimmed.substring(2).trim();
                int colon = kv.indexOf(':');
                if (colon < 0) continue;
                String key = kv.substring(0, colon).trim().toLowerCase();
                String val = kv.substring(colon + 1).trim();

                if (key.equals("mic_device") && !val.isBlank()) {
                    micDevice = val;
                }
            }
        } catch (IOException ignored) {}
    }

    // ═══ Audio capture ═══

    private void runAudioChat() {
        try {
            byte[] wav = captureWavAudio();
            if (stopRequested) return;
            if (wav == null || wav.length == 0) {
                error = "No audio captured.";
                return;
            }
            String reply = chatService.getReplyFromAudio(wav);
            transcriptOrReply = reply == null ? "" : reply;
        } catch (Exception ex) {
            if (!stopRequested) {
                error = ex.getMessage() == null ? "Audio processing error." : ex.getMessage();
            }
        } finally {
            synchronized (lock) {
                activeLine = null;
                listening = false;
            }
        }
    }

    private byte[] captureWavAudio() throws Exception {
        AudioFormat format = new AudioFormat(SAMPLE_RATE, SAMPLE_SIZE_BITS, CHANNELS, true, false);
        TargetDataLine line = findTargetDataLine(format);
        synchronized (lock) {
            activeLine = line;
        }
        line.open(format);
        line.start();

        long endAt = System.currentTimeMillis() + (CAPTURE_SECONDS * 1000L);
        ByteArrayOutputStream pcmOut = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];

        while (!stopRequested && System.currentTimeMillis() < endAt) {
            int read = line.read(buffer, 0, buffer.length);
            if (read > 0) {
                pcmOut.write(buffer, 0, read);
            }
        }

        line.stop();
        line.close();

        byte[] pcm = pcmOut.toByteArray();
        if (pcm.length == 0) return new byte[0];

        ByteArrayInputStream bais = new ByteArrayInputStream(pcm);
        long frameLength = pcm.length / format.getFrameSize();
        AudioInputStream ais = new AudioInputStream(bais, format, frameLength);
        ByteArrayOutputStream wavOut = new ByteArrayOutputStream();
        AudioSystem.write(ais, AudioFileFormat.Type.WAVE, wavOut);
        return wavOut.toByteArray();
    }

    /**
     * Find the TargetDataLine for the configured mic device (or system default).
     * If micDevice is set, searches for a mixer whose name contains the configured string.
     */
    private TargetDataLine findTargetDataLine(AudioFormat format) throws LineUnavailableException {
        String preferred = micDevice;
        if (preferred != null && !preferred.isBlank()) {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            String lowerPref = preferred.toLowerCase();
            for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
                if (mixerInfo.getName().toLowerCase().contains(lowerPref)) {
                    try {
                        Mixer mixer = AudioSystem.getMixer(mixerInfo);
                        if (mixer.isLineSupported(info)) {
                            return (TargetDataLine) mixer.getLine(info);
                        }
                    } catch (Exception ignored) {}
                }
            }
            // Exact match not found — warn but continue with default
            System.err.println("[NativeVoice] Configured mic_device '" + preferred
                    + "' not found. Available: " + listMicrophones() + ". Using default.");
        }
        // Fall back to system default
        return AudioSystem.getTargetDataLine(format);
    }
}
