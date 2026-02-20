package com.botsfer.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Plays a low-volume dial-up modem sound while the bot is processing.
 * The sound is synthesized in memory — no external audio file needed.
 */
@Component
public class WorkingSoundService {

    private static final Logger log = LoggerFactory.getLogger(WorkingSoundService.class);

    private static final float SAMPLE_RATE = 22050f;
    private static final float VOLUME = 0.20f; // 20% volume

    private volatile Clip clip;
    private volatile boolean playing = false;
    private byte[] dialUpWav;

    /** Start looping the dial-up sound. Safe to call multiple times. */
    public synchronized void start() {
        if (playing) return;
        try {
            if (dialUpWav == null) {
                dialUpWav = generateDialUpSound();
            }
            AudioInputStream ais = AudioSystem.getAudioInputStream(
                    new ByteArrayInputStream(dialUpWav));
            clip = AudioSystem.getClip();
            clip.open(ais);

            // Set volume to 20%
            if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                // Convert linear 0.0–1.0 to decibels
                float dB = 20f * (float) Math.log10(VOLUME);
                dB = Math.max(dB, gain.getMinimum());
                dB = Math.min(dB, gain.getMaximum());
                gain.setValue(dB);
            }

            clip.loop(Clip.LOOP_CONTINUOUSLY);
            playing = true;
        } catch (Exception e) {
            log.debug("Could not play working sound: {}", e.getMessage());
        }
    }

    /** Stop the sound. Safe to call even if not playing. */
    public synchronized void stop() {
        playing = false;
        if (clip != null) {
            try {
                clip.stop();
                clip.close();
            } catch (Exception ignored) {
            }
            clip = null;
        }
    }

    public boolean isPlaying() {
        return playing;
    }

    /**
     * Synthesize a ~4-second dial-up modem sound as a WAV byte array.
     * Mimics the classic sequence: dial tone -> handshake screech -> carrier.
     */
    private byte[] generateDialUpSound() throws IOException {
        int totalSamples = (int) (SAMPLE_RATE * 4); // 4 seconds
        byte[] pcm = new byte[totalSamples];

        for (int i = 0; i < totalSamples; i++) {
            double t = i / (double) SAMPLE_RATE;
            double sample = 0;

            if (t < 0.6) {
                // Phase 1: Dial tone (350Hz + 440Hz mixed)
                sample = 0.4 * Math.sin(2 * Math.PI * 350 * t)
                       + 0.4 * Math.sin(2 * Math.PI * 440 * t);
            } else if (t < 1.2) {
                // Phase 2: DTMF-like dialing tones (rapid frequency hops)
                double freq = 800 + 400 * Math.sin(2 * Math.PI * 12 * t);
                sample = 0.5 * Math.sin(2 * Math.PI * freq * t);
            } else if (t < 2.4) {
                // Phase 3: Modem handshake screech (swept frequencies + noise)
                double sweep = 1200 + 800 * Math.sin(2 * Math.PI * 3.5 * t);
                sample = 0.3 * Math.sin(2 * Math.PI * sweep * t)
                       + 0.2 * Math.sin(2 * Math.PI * (sweep * 1.5) * t)
                       + 0.1 * (Math.random() * 2 - 1); // slight noise
            } else {
                // Phase 4: Carrier tone with warble (steady hiss + tone)
                sample = 0.35 * Math.sin(2 * Math.PI * 1650 * t)
                       + 0.15 * Math.sin(2 * Math.PI * 1850 * t)
                       + 0.05 * Math.sin(2 * Math.PI * 7.5 * t) // slow warble
                               * Math.sin(2 * Math.PI * 1650 * t);
            }

            // Soft crossfade between phases
            double envelope = 1.0;
            if (t < 0.01) envelope = t / 0.01;                         // fade in
            else if (t > 3.95) envelope = (4.0 - t) / 0.05;           // fade out
            else if (Math.abs(t - 0.6) < 0.02) envelope = 0.7;        // transition softener
            else if (Math.abs(t - 1.2) < 0.02) envelope = 0.7;
            else if (Math.abs(t - 2.4) < 0.02) envelope = 0.7;

            // Clamp and write as signed 8-bit PCM
            sample *= envelope;
            sample = Math.max(-1, Math.min(1, sample));
            pcm[i] = (byte) (sample * 127);
        }

        // Wrap PCM data into a WAV file
        return wrapWav(pcm, SAMPLE_RATE);
    }

    /** Create a minimal WAV file from raw 8-bit signed PCM mono data. */
    private byte[] wrapWav(byte[] pcm, float sampleRate) throws IOException {
        int sr = (int) sampleRate;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();

        // RIFF header
        bos.write("RIFF".getBytes());
        writeInt(bos, 36 + pcm.length);  // chunk size
        bos.write("WAVE".getBytes());

        // fmt sub-chunk
        bos.write("fmt ".getBytes());
        writeInt(bos, 16);               // sub-chunk size
        writeShort(bos, 1);              // PCM format
        writeShort(bos, 1);              // mono
        writeInt(bos, sr);               // sample rate
        writeInt(bos, sr);               // byte rate (sr * 1 channel * 1 byte)
        writeShort(bos, 1);              // block align
        writeShort(bos, 8);              // bits per sample

        // data sub-chunk
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
}
