package com.minsbot.agent;

import com.minsbot.agent.tools.TtsTools;
import com.minsbot.agent.tools.ToolExecutionNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;

/**
 * JARVIS-style barge-in: while TTS is speaking, listens on the mic and
 * interrupts playback as soon as the user starts talking.
 *
 * <p>Algorithm (v1, no external deps):
 * <ol>
 *   <li>When {@link TtsTools#isSpeaking()} becomes true, open a mic line.</li>
 *   <li>Calibrate the noise floor for the first {@code warmupMs} (avg RMS of ambient).</li>
 *   <li>On each 50 ms frame after warmup, compute RMS. If RMS exceeds
 *       {@code noiseFloor * thresholdMultiplier} for {@code consecutiveFrames}
 *       frames in a row, call {@link TtsTools#stopPlayback()}.</li>
 *   <li>Close the mic when TTS stops.</li>
 * </ol>
 *
 * <p>Feedback-loop caveat: if the user has loud speakers, TTS leakage into the
 * mic can trip the threshold. Mitigations: (1) adaptive noise floor calibrated
 * during the first TTS audio, (2) a high default multiplier, (3) configurable.
 */
@Service
public class BargeInService {

    private static final Logger log = LoggerFactory.getLogger(BargeInService.class);

    // Audio format: 16 kHz, 16-bit PCM, mono — same as NativeVoiceService for consistency.
    private static final float SAMPLE_RATE = 16_000f;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int CHANNELS = 1;
    private static final int FRAME_BYTES = (int) (SAMPLE_RATE * 2 * 0.05); // 50 ms @ 16-bit mono = 1600 bytes

    private final TtsTools ttsTools;
    private final ToolExecutionNotifier notifier;

    @Value("${app.bargein.enabled:true}")
    private boolean enabled;

    /** Multiplier applied to the calibrated noise floor. Higher = harder to trigger. */
    @Value("${app.bargein.threshold-multiplier:5.0}")
    private double thresholdMultiplier;

    /** Absolute minimum RMS to ever trigger, regardless of noise floor. Avoids false positives in dead-silent rooms. */
    @Value("${app.bargein.min-rms:450}")
    private double minRms;

    /** How many consecutive frames must exceed threshold to trigger (each ~50 ms). */
    @Value("${app.bargein.consecutive-frames:3}")
    private int consecutiveFrames;

    /** Warmup period after TTS starts before we'll consider interrupting (noise-floor calibration). */
    @Value("${app.bargein.warmup-ms:300}")
    private int warmupMs;

    private volatile Thread watcherThread;
    private volatile boolean running = false;

    public BargeInService(@Lazy TtsTools ttsTools, ToolExecutionNotifier notifier) {
        this.ttsTools = ttsTools;
        this.notifier = notifier;
    }

    @PostConstruct
    public void start() {
        if (!enabled) {
            log.info("[BargeIn] Disabled via app.bargein.enabled=false");
            return;
        }
        running = true;
        watcherThread = new Thread(this::watcherLoop, "bargein-watcher");
        watcherThread.setDaemon(true);
        watcherThread.start();
        log.info("[BargeIn] Watcher started (threshold×{}, minRms={}, {} consecutive frames, {} ms warmup)",
                thresholdMultiplier, (int) minRms, consecutiveFrames, warmupMs);
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (watcherThread != null) watcherThread.interrupt();
    }

    /**
     * Main loop: idles while TTS is silent (polls every 200 ms),
     * kicks off a barge-in listening session when TTS starts speaking.
     */
    private void watcherLoop() {
        while (running) {
            try {
                if (!ttsTools.isSpeaking()) {
                    Thread.sleep(200);
                    continue;
                }
                // TTS just started — run one listening session until either user interrupts or TTS stops.
                runListeningSession();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("[BargeIn] Watcher error: {}", e.getMessage());
                try { Thread.sleep(500); } catch (InterruptedException ignored) { return; }
            }
        }
    }

    /**
     * Opens the mic, calibrates noise floor, listens for speech while TTS is speaking.
     * Returns when TTS stops or user speech is detected (triggering stopPlayback).
     */
    private void runListeningSession() {
        AudioFormat format = new AudioFormat(SAMPLE_RATE, BITS_PER_SAMPLE, CHANNELS, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        TargetDataLine line = null;

        try {
            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(format, FRAME_BYTES * 4);
            line.start();
            log.debug("[BargeIn] Mic opened for barge-in listening");

            byte[] buf = new byte[FRAME_BYTES];
            long sessionStartMs = System.currentTimeMillis();
            double noiseFloor = 0;
            int calibrationFrames = 0;
            int consecutiveAbove = 0;

            while (running && ttsTools.isSpeaking()) {
                int bytesRead = line.read(buf, 0, buf.length);
                if (bytesRead <= 0) continue;

                double rms = computeRms(buf, bytesRead);
                long elapsed = System.currentTimeMillis() - sessionStartMs;

                // Calibration phase — average ambient RMS.
                if (elapsed < warmupMs) {
                    noiseFloor = (noiseFloor * calibrationFrames + rms) / (calibrationFrames + 1);
                    calibrationFrames++;
                    continue;
                }

                double threshold = Math.max(minRms, noiseFloor * thresholdMultiplier);
                if (rms > threshold) {
                    consecutiveAbove++;
                    if (consecutiveAbove >= consecutiveFrames) {
                        log.info("[BargeIn] User speech detected (rms={}, threshold={}, noiseFloor={}) — interrupting TTS",
                                (int) rms, (int) threshold, (int) noiseFloor);
                        notifier.notify("Interrupting TTS — you're talking");
                        ttsTools.stopPlayback();
                        return;
                    }
                } else {
                    consecutiveAbove = 0;
                }
            }
            log.debug("[BargeIn] Session ended without interrupt (TTS stopped first)");
        } catch (LineUnavailableException e) {
            // Another service may hold the mic exclusively — not fatal, just wait for TTS to finish.
            log.debug("[BargeIn] Mic unavailable this session: {}", e.getMessage());
            waitForTtsToFinish();
        } catch (Exception e) {
            log.warn("[BargeIn] Session error: {}", e.getMessage());
        } finally {
            if (line != null) {
                try { line.stop(); } catch (Exception ignored) {}
                try { line.close(); } catch (Exception ignored) {}
            }
        }
    }

    /** When we can't open the mic, just idle until TTS finishes so we don't spin. */
    private void waitForTtsToFinish() {
        try {
            while (running && ttsTools.isSpeaking()) {
                Thread.sleep(200);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** RMS of signed 16-bit little-endian PCM. */
    private static double computeRms(byte[] buf, int len) {
        long sumSquares = 0;
        int samples = len / 2;
        for (int i = 0; i < len - 1; i += 2) {
            int sample = (buf[i] & 0xFF) | (buf[i + 1] << 8);
            sumSquares += (long) sample * sample;
        }
        return samples == 0 ? 0 : Math.sqrt((double) sumSquares / samples);
    }

    // ─── Runtime controls (can be wired to a sensory toggle later) ──────────

    public boolean isEnabled() { return enabled; }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        log.info("[BargeIn] Runtime enabled={}", enabled);
    }
}
