package com.minsbot.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Path;

/**
 * Continuous audio capture pipeline: WASAPI exe (--stdout) → FFmpeg (resample) → ring buffer.
 *
 * <p>Only two long-running processes for the entire listen session. Chunks are read from the ring
 * buffer instantly — no subprocess overhead per chunk. Supports overlapping reads so no words
 * are missed between translations.</p>
 *
 * <p>Ring buffer stores 16kHz mono 16-bit PCM (32,000 bytes/sec). Default capacity: 16 seconds
 * (512KB). Reading the last N seconds is O(1) — just a memcpy from the circular buffer.</p>
 */
@Component
public class AudioPipelineService {

    private static final Logger log = LoggerFactory.getLogger(AudioPipelineService.class);

    /** 16kHz mono 16-bit = 32,000 bytes per second. */
    private static final int BYTES_PER_SECOND = 16000 * 2;

    /** Ring buffer capacity in seconds. */
    private static final int BUFFER_SECONDS = 16;

    /** Ring buffer size in bytes (16s × 32KB/s = 512KB). */
    private static final int BUFFER_SIZE = BYTES_PER_SECOND * BUFFER_SECONDS;

    private final AudioMemoryService audioMemoryService;

    // Ring buffer
    private final byte[] ring = new byte[BUFFER_SIZE];
    private volatile int writePos = 0;
    private volatile long totalBytesWritten = 0;
    private volatile long lastStreamedTotal = 0;  // for incremental readNewAudio()
    private final Object ringLock = new Object();

    // Processes
    private volatile Process wasapiProcess;
    private volatile Process ffmpegProcess;
    private volatile Thread piperThread;
    private volatile Thread readerThread;
    private volatile boolean running = false;

    public AudioPipelineService(AudioMemoryService audioMemoryService) {
        this.audioMemoryService = audioMemoryService;
    }

    public boolean isRunning() {
        return running && wasapiProcess != null && wasapiProcess.isAlive()
                && ffmpegProcess != null && ffmpegProcess.isAlive();
    }

    /**
     * Start the continuous capture pipeline.
     * WASAPI exe streams raw PCM to stdout → piped to FFmpeg stdin → FFmpeg outputs
     * 16kHz mono s16le to stdout → reader thread fills ring buffer.
     */
    public synchronized void start() {
        if (running) return;

        if (!audioMemoryService.isWasapiAvailable()) {
            log.warn("[AudioPipeline] WASAPI not available, cannot start pipeline");
            return;
        }

        Path wasapiExe = audioMemoryService.getWasapiExePath();
        FfmpegProvider ffmpegProvider = audioMemoryService.getFfmpegProviderRef();
        Path ffmpeg = ffmpegProvider.getFfmpegPath();
        if (ffmpeg == null) {
            log.warn("[AudioPipeline] FFmpeg not available, cannot start pipeline");
            return;
        }

        // Reset ring buffer
        synchronized (ringLock) {
            writePos = 0;
            totalBytesWritten = 0;
            lastStreamedTotal = 0;
        }

        try {
            // Start WASAPI in stdout streaming mode (1 hour max)
            ProcessBuilder wasapiPb = new ProcessBuilder(
                    wasapiExe.toAbsolutePath().toString(), "--stdout", "3600"
            );
            wasapiPb.redirectErrorStream(false);
            wasapiProcess = wasapiPb.start();

            // Read format line from stderr: "FORMAT 48000 2 32"
            Thread formatReader = new Thread(() -> {
                try {
                    InputStream stderr = wasapiProcess.getErrorStream();
                    byte[] buf = new byte[256];
                    int n = stderr.read(buf);
                    if (n > 0) {
                        String line = new String(buf, 0, n).trim();
                        log.info("[AudioPipeline] WASAPI format: {}", line);
                    }
                    // Keep reading stderr to prevent blocking
                    while (stderr.read(buf) >= 0) { /* drain */ }
                } catch (Exception e) {
                    if (running) log.debug("[AudioPipeline] WASAPI stderr read ended: {}", e.getMessage());
                }
            }, "audio-pipe-wasapi-err");
            formatReader.setDaemon(true);
            formatReader.start();

            // Small delay for WASAPI to start producing data
            Thread.sleep(300);

            if (!wasapiProcess.isAlive()) {
                log.warn("[AudioPipeline] WASAPI process died immediately");
                stop();
                return;
            }

            // Start FFmpeg: read raw float32 stereo 48kHz from stdin, output s16le mono 16kHz to stdout
            // We use a generic input format that matches typical WASAPI output
            ProcessBuilder ffmpegPb = new ProcessBuilder(
                    ffmpeg.toAbsolutePath().toString(),
                    "-f", "f32le", "-ar", "48000", "-ac", "2", "-i", "pipe:0",
                    "-ar", "16000", "-ac", "1", "-f", "s16le", "pipe:1"
            );
            ffmpegPb.redirectErrorStream(false);
            ffmpegProcess = ffmpegPb.start();

            // Drain FFmpeg stderr to prevent blocking
            Thread ffmpegErrDrain = new Thread(() -> {
                try {
                    InputStream stderr = ffmpegProcess.getErrorStream();
                    byte[] buf = new byte[4096];
                    while (stderr.read(buf) >= 0) { /* drain */ }
                } catch (Exception e) { /* ignore */ }
            }, "audio-pipe-ffmpeg-err");
            ffmpegErrDrain.setDaemon(true);
            ffmpegErrDrain.start();

            running = true;

            // Piper thread: WASAPI stdout → FFmpeg stdin
            piperThread = new Thread(() -> {
                try {
                    InputStream wasapiOut = wasapiProcess.getInputStream();
                    var ffmpegIn = ffmpegProcess.getOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while (running && (n = wasapiOut.read(buf)) >= 0) {
                        ffmpegIn.write(buf, 0, n);
                        ffmpegIn.flush();
                    }
                } catch (Exception e) {
                    if (running) log.debug("[AudioPipeline] Piper ended: {}", e.getMessage());
                } finally {
                    try { ffmpegProcess.getOutputStream().close(); } catch (Exception ignored) {}
                }
            }, "audio-pipe-piper");
            piperThread.setDaemon(true);
            piperThread.start();

            // Reader thread: FFmpeg stdout → ring buffer
            readerThread = new Thread(() -> {
                try {
                    InputStream ffmpegOut = ffmpegProcess.getInputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while (running && (n = ffmpegOut.read(buf)) >= 0) {
                        synchronized (ringLock) {
                            for (int i = 0; i < n; i++) {
                                ring[writePos] = buf[i];
                                writePos = (writePos + 1) % BUFFER_SIZE;
                            }
                            totalBytesWritten += n;
                        }
                    }
                } catch (Exception e) {
                    if (running) log.debug("[AudioPipeline] Reader ended: {}", e.getMessage());
                }
            }, "audio-pipe-reader");
            readerThread.setDaemon(true);
            readerThread.start();

            log.info("[AudioPipeline] Started — continuous WASAPI → FFmpeg → ring buffer");

        } catch (Exception e) {
            log.warn("[AudioPipeline] Failed to start: {}", e.getMessage());
            stop();
        }
    }

    /**
     * Read the last N seconds of audio from the ring buffer.
     * Returns 16kHz mono 16-bit PCM bytes, or null if not enough data.
     */
    public byte[] readLastSeconds(int seconds) {
        int bytesNeeded = seconds * BYTES_PER_SECOND;
        if (bytesNeeded > BUFFER_SIZE) bytesNeeded = BUFFER_SIZE;

        synchronized (ringLock) {
            long available = Math.min(totalBytesWritten, BUFFER_SIZE);
            if (available < bytesNeeded) {
                // Not enough data yet — return what we have
                if (available < BYTES_PER_SECOND) return null; // less than 1 second
                bytesNeeded = (int) available;
            }

            byte[] result = new byte[bytesNeeded];
            int startPos = (writePos - bytesNeeded + BUFFER_SIZE) % BUFFER_SIZE;

            if (startPos + bytesNeeded <= BUFFER_SIZE) {
                // Contiguous read
                System.arraycopy(ring, startPos, result, 0, bytesNeeded);
            } else {
                // Wraps around
                int firstPart = BUFFER_SIZE - startPos;
                System.arraycopy(ring, startPos, result, 0, firstPart);
                System.arraycopy(ring, 0, result, firstPart, bytesNeeded - firstPart);
            }

            return result;
        }
    }

    /**
     * Read only the NEW audio written since the last call to this method.
     * Returns null if no new data. Used for continuous streaming to Gemini Live
     * so every second of audio is sent exactly once — no gaps, no overlap.
     */
    public byte[] readNewAudio() {
        synchronized (ringLock) {
            long newBytes = totalBytesWritten - lastStreamedTotal;
            if (newBytes <= 0) return null;

            // If we fell behind (buffer wrapped), read what's available
            if (newBytes > BUFFER_SIZE) newBytes = BUFFER_SIZE;

            int count = (int) newBytes;
            byte[] result = new byte[count];
            int startPos = (writePos - count + BUFFER_SIZE) % BUFFER_SIZE;

            if (startPos + count <= BUFFER_SIZE) {
                System.arraycopy(ring, startPos, result, 0, count);
            } else {
                int firstPart = BUFFER_SIZE - startPos;
                System.arraycopy(ring, startPos, result, 0, firstPart);
                System.arraycopy(ring, 0, result, firstPart, count - firstPart);
            }

            lastStreamedTotal = totalBytesWritten;
            return result;
        }
    }

    /** Stop the pipeline and kill processes. */
    public synchronized void stop() {
        running = false;

        if (wasapiProcess != null) {
            wasapiProcess.destroyForcibly();
            wasapiProcess = null;
        }
        if (ffmpegProcess != null) {
            ffmpegProcess.destroyForcibly();
            ffmpegProcess = null;
        }
        if (piperThread != null) {
            piperThread.interrupt();
            piperThread = null;
        }
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }

        log.info("[AudioPipeline] Stopped");
    }
}
