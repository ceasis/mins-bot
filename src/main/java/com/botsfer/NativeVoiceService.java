package com.botsfer;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native mic capture -> LLM audio request.
 * Captures a short WAV clip and asks ChatService for a direct audio reply.
 */
public class NativeVoiceService {

    private static final int CAPTURE_SECONDS = 8;
    private static final float SAMPLE_RATE = 16000f;
    private static final int SAMPLE_SIZE_BITS = 16;
    private static final int CHANNELS = 1;

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

    public NativeVoiceService(ChatService chatService) {
        this.chatService = chatService;
    }

    public boolean isAvailable() {
        try {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, SAMPLE_SIZE_BITS, CHANNELS, true, false);
            return AudioSystem.isLineSupported(new javax.sound.sampled.DataLine.Info(TargetDataLine.class, format));
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
        TargetDataLine line = AudioSystem.getTargetDataLine(format);
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
}
