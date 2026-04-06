package com.minsbot.agent.tools;

import com.minsbot.ChatService;
import com.minsbot.agent.AsyncMessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import javax.sound.sampled.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Voice wake word detection — always-listening background service that activates
 * on a configurable wake phrase (default: "Hey Jarvis"). When the wake word is
 * detected, captures a follow-up command and sends it to the chat.
 *
 * <p>Uses short mic captures (1.5s) with silence detection to avoid wasting
 * Whisper API calls. Only non-silent audio is transcribed.</p>
 */
@Component
public class WakeWordTools {

    private static final Logger log = LoggerFactory.getLogger(WakeWordTools.class);
    private static final float SAMPLE_RATE = 16000f;
    private static final int SAMPLE_SIZE_BITS = 16;
    private static final int CHANNELS = 1;
    private static final int WAKE_CLIP_SECONDS = 2;
    private static final int COMMAND_CLIP_SECONDS = 6;
    private static final int SILENCE_THRESHOLD = 300;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final Path CONFIG_PATH =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "minsbot_config.txt");

    private final ChatService chatService;
    private final AsyncMessageService asyncMessages;
    private final NotificationTools notificationTools;
    private final TtsTools ttsTools;
    private final ToolExecutionNotifier notifier;

    private volatile boolean active = false;
    private volatile String wakeWord = "hey jarvis";
    private volatile Thread listenerThread;
    private volatile String micDevice = null;
    private final AtomicInteger wakeCount = new AtomicInteger(0);
    private final AtomicInteger commandCount = new AtomicInteger(0);
    private volatile String lastCommand = null;
    private volatile String startedAt = null;

    public WakeWordTools(ChatService chatService,
                         AsyncMessageService asyncMessages,
                         NotificationTools notificationTools,
                         TtsTools ttsTools,
                         ToolExecutionNotifier notifier) {
        this.chatService = chatService;
        this.asyncMessages = asyncMessages;
        this.notificationTools = notificationTools;
        this.ttsTools = ttsTools;
        this.notifier = notifier;
    }

    @Tool(description = "Start always-listening wake word detection. Continuously listens to the microphone " +
            "for a wake phrase (default 'Hey Jarvis'). When detected, captures a follow-up voice command " +
            "and sends it to the chat. Use when the user says 'enable wake word', 'listen for hey jarvis', " +
            "'turn on voice activation', 'always listen for my voice', 'activate voice trigger'.")
    public String startWakeWord(
            @ToolParam(description = "Wake word/phrase to listen for, e.g. 'Hey Jarvis', 'Hey Mins', 'OK Computer'. Default: 'Hey Jarvis'")
            String wakePhrase) {

        if (active) {
            return "Wake word detection is already running (wake word: \"" + wakeWord + "\"). Stop it first.";
        }

        if (wakePhrase != null && !wakePhrase.isBlank()) {
            this.wakeWord = wakePhrase.trim().toLowerCase();
        }

        notifier.notify("Starting wake word detection: \"" + wakeWord + "\"...");
        loadMicConfig();

        // Verify mic is available
        AudioFormat format = new AudioFormat(SAMPLE_RATE, SAMPLE_SIZE_BITS, CHANNELS, true, false);
        if (!AudioSystem.isLineSupported(new DataLine.Info(TargetDataLine.class, format))) {
            return "No microphone available. Cannot start wake word detection.";
        }

        active = true;
        wakeCount.set(0);
        commandCount.set(0);
        lastCommand = null;
        startedAt = LocalDateTime.now().format(FMT);

        listenerThread = new Thread(this::listenLoop, "wake-word-listener");
        listenerThread.setDaemon(true);
        listenerThread.start();

        log.info("[WakeWord] Started listening for: \"{}\"", wakeWord);

        return "Wake word detection started!\n"
                + "Wake phrase: \"" + wakeWord + "\"\n"
                + "Say \"" + wakeWord + "\" followed by your command.\n"
                + "Example: \"" + wakeWord + ", what's the weather today?\"\n"
                + "Use stopWakeWord to disable.";
    }

    @Tool(description = "Stop wake word detection. Use when the user says 'stop listening', " +
            "'disable wake word', 'turn off voice activation', 'stop hey jarvis'.")
    public String stopWakeWord() {
        if (!active) return "Wake word detection is not running.";

        notifier.notify("Stopping wake word detection...");
        active = false;

        Thread t = listenerThread;
        if (t != null) {
            t.interrupt();
            try { t.join(3000); } catch (InterruptedException ignored) {}
        }
        listenerThread = null;

        log.info("[WakeWord] Stopped. Wakes: {}, Commands: {}", wakeCount.get(), commandCount.get());

        return "Wake word detection stopped.\n"
                + "Total wake detections: " + wakeCount.get() + "\n"
                + "Commands processed: " + commandCount.get();
    }

    @Tool(description = "Change the wake word/phrase without restarting. Use when the user says " +
            "'change wake word to X', 'use hey computer instead'.")
    public String changeWakeWord(
            @ToolParam(description = "New wake phrase, e.g. 'Hey Computer', 'OK Bot'") String newPhrase) {
        if (newPhrase == null || newPhrase.isBlank()) return "Wake phrase cannot be empty.";
        String old = this.wakeWord;
        this.wakeWord = newPhrase.trim().toLowerCase();
        notifier.notify("Wake word changed: \"" + old + "\" → \"" + wakeWord + "\"");
        log.info("[WakeWord] Changed: \"{}\" → \"{}\"", old, wakeWord);
        return "Wake word changed from \"" + old + "\" to \"" + wakeWord + "\"."
                + (active ? " Still listening." : " Not currently active — use startWakeWord to begin.");
    }

    @Tool(description = "Get the current wake word detection status — whether it's running, the wake phrase, " +
            "and statistics (wake count, commands processed).")
    public String getWakeWordStatus() {
        notifier.notify("Checking wake word status...");
        if (!active) return "Wake word detection is OFF.";
        return "Wake word detection: ON\n"
                + "Wake phrase: \"" + wakeWord + "\"\n"
                + "Started: " + startedAt + "\n"
                + "Wake detections: " + wakeCount.get() + "\n"
                + "Commands processed: " + commandCount.get() + "\n"
                + (lastCommand != null ? "Last command: \"" + lastCommand + "\"" : "");
    }

    public boolean isActive() { return active; }
    public String getWakeWord() { return wakeWord; }

    // ─── Listener loop ───────────────────────────────────────────────────

    private void listenLoop() {
        AudioFormat format = new AudioFormat(SAMPLE_RATE, SAMPLE_SIZE_BITS, CHANNELS, true, false);

        while (active && !Thread.currentThread().isInterrupted()) {
            TargetDataLine line = null;
            try {
                line = findMicLine(format);
                if (line == null) {
                    log.warn("[WakeWord] No mic line found — retrying in 5s");
                    Thread.sleep(5000);
                    continue;
                }

                line.open(format);
                line.start();

                while (active && !Thread.currentThread().isInterrupted()) {
                    // Capture a short clip for wake word detection
                    byte[] clip = captureClip(line, format, WAKE_CLIP_SECONDS);
                    if (!active) break;

                    // Skip silent clips
                    if (isSilent(clip)) continue;

                    // Transcribe
                    String text = transcribe(clip);
                    if (text == null || text.isBlank()) continue;

                    String lower = text.toLowerCase().trim();
                    log.debug("[WakeWord] Heard: \"{}\"", lower);

                    // Check for wake word
                    if (containsWakeWord(lower)) {
                        wakeCount.incrementAndGet();
                        log.info("[WakeWord] WAKE DETECTED in: \"{}\"", text);

                        // Extract any command that came after the wake word in the same clip
                        String inlineCommand = extractCommandAfterWake(lower);

                        if (inlineCommand != null && inlineCommand.length() > 3) {
                            // Command was in the same breath
                            processCommand(inlineCommand);
                        } else {
                            // Play a short acknowledgment and capture command
                            try {
                                ttsTools.speak("Yes?");
                            } catch (Exception ignored) {}

                            // Capture the follow-up command (longer clip)
                            byte[] cmdClip = captureClip(line, format, COMMAND_CLIP_SECONDS);
                            if (!active) break;

                            if (!isSilent(cmdClip)) {
                                String command = transcribe(cmdClip);
                                if (command != null && !command.isBlank() && command.trim().length() > 2) {
                                    processCommand(command.trim());
                                }
                            }
                        }
                    }
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("[WakeWord] Error: {} — retrying in 3s", e.getMessage());
                try { Thread.sleep(3000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } finally {
                if (line != null) {
                    try { line.stop(); line.close(); } catch (Exception ignored) {}
                }
            }
        }

        log.info("[WakeWord] Listener loop ended");
    }

    private void processCommand(String command) {
        commandCount.incrementAndGet();
        lastCommand = command;
        log.info("[WakeWord] Processing command: \"{}\"", command);

        // Show notification
        try {
            notificationTools.showNotification("Voice Command", command);
        } catch (Exception ignored) {}

        // Push to chat as if the user typed it
        asyncMessages.push("__VOICE_CMD__" + command);

        // Also send to chat service for a reply
        try {
            String reply = chatService.getReply(command);
            if (reply != null && !reply.isBlank()) {
                asyncMessages.push(reply);
            }
        } catch (Exception e) {
            log.warn("[WakeWord] Command processing failed: {}", e.getMessage());
        }
    }

    private boolean containsWakeWord(String text) {
        // Fuzzy matching — handle common Whisper mishearings
        String normalized = text.replaceAll("[^a-z0-9 ]", "").trim();
        String wake = wakeWord.replaceAll("[^a-z0-9 ]", "").trim();

        if (normalized.contains(wake)) return true;

        // Check common variations
        String[] wakeWords = wake.split("\\s+");
        if (wakeWords.length >= 2) {
            // Check if all words of the wake phrase appear in order
            int pos = 0;
            for (String w : wakeWords) {
                int idx = normalized.indexOf(w, pos);
                if (idx < 0) return false;
                pos = idx + w.length();
            }
            return true;
        }

        return false;
    }

    private String extractCommandAfterWake(String text) {
        String wake = wakeWord.replaceAll("[^a-z0-9 ]", "").trim();
        int idx = text.indexOf(wake);
        if (idx < 0) return null;
        String after = text.substring(idx + wake.length()).trim();
        // Remove leading punctuation/filler
        after = after.replaceAll("^[,\\.!\\? ]+", "").trim();
        return after.isEmpty() ? null : after;
    }

    // ─── Audio helpers ───────────────────────────────────────────────────

    private byte[] captureClip(TargetDataLine line, AudioFormat format, int seconds) throws InterruptedException {
        int bytesPerSecond = (int) (format.getSampleRate() * format.getFrameSize());
        int totalBytes = bytesPerSecond * seconds;
        byte[] buffer = new byte[4096];
        ByteArrayOutputStream bos = new ByteArrayOutputStream();

        int remaining = totalBytes;
        while (remaining > 0 && active) {
            int toRead = Math.min(buffer.length, remaining);
            int read = line.read(buffer, 0, toRead);
            if (read <= 0) break;
            bos.write(buffer, 0, read);
            remaining -= read;
        }

        return buildWav(bos.toByteArray(), format);
    }

    private boolean isSilent(byte[] wav) {
        if (wav == null || wav.length < 44) return true;
        int loudSamples = 0;
        int totalSamples = 0;
        for (int i = 44; i < wav.length - 1; i += 2) {
            short sample = (short) ((wav[i + 1] << 8) | (wav[i] & 0xFF));
            totalSamples++;
            if (Math.abs(sample) > SILENCE_THRESHOLD) loudSamples++;
        }
        // Consider silent if less than 1% of samples are above threshold
        return totalSamples == 0 || (double) loudSamples / totalSamples < 0.01;
    }

    private String transcribe(byte[] wav) {
        try {
            return chatService.transcribeAudio(wav);
        } catch (Exception e) {
            log.debug("[WakeWord] Transcription failed: {}", e.getMessage());
            return null;
        }
    }

    private TargetDataLine findMicLine(AudioFormat format) {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

        // Try configured mic device first
        if (micDevice != null && !micDevice.isBlank()) {
            for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                if (mi.getName().toLowerCase().contains(micDevice.toLowerCase())) {
                    try {
                        Mixer mixer = AudioSystem.getMixer(mi);
                        if (mixer.isLineSupported(info)) {
                            return (TargetDataLine) mixer.getLine(info);
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        // Fall back to system default
        try {
            if (AudioSystem.isLineSupported(info)) {
                return (TargetDataLine) AudioSystem.getLine(info);
            }
        } catch (Exception ignored) {}

        return null;
    }

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

    private byte[] buildWav(byte[] pcm, AudioFormat format) {
        int dataSize = pcm.length;
        int totalSize = 44 + dataSize;
        byte[] wav = new byte[totalSize];

        // RIFF header
        wav[0] = 'R'; wav[1] = 'I'; wav[2] = 'F'; wav[3] = 'F';
        writeInt(wav, 4, totalSize - 8);
        wav[8] = 'W'; wav[9] = 'A'; wav[10] = 'V'; wav[11] = 'E';

        // fmt chunk
        wav[12] = 'f'; wav[13] = 'm'; wav[14] = 't'; wav[15] = ' ';
        writeInt(wav, 16, 16); // chunk size
        writeShort(wav, 20, (short) 1); // PCM
        writeShort(wav, 22, (short) format.getChannels());
        writeInt(wav, 24, (int) format.getSampleRate());
        writeInt(wav, 28, (int) (format.getSampleRate() * format.getFrameSize()));
        writeShort(wav, 32, (short) format.getFrameSize());
        writeShort(wav, 34, (short) format.getSampleSizeInBits());

        // data chunk
        wav[36] = 'd'; wav[37] = 'a'; wav[38] = 't'; wav[39] = 'a';
        writeInt(wav, 40, dataSize);

        System.arraycopy(pcm, 0, wav, 44, dataSize);
        return wav;
    }

    private static void writeInt(byte[] buf, int offset, int value) {
        buf[offset] = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
        buf[offset + 2] = (byte) ((value >> 16) & 0xFF);
        buf[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static void writeShort(byte[] buf, int offset, short value) {
        buf[offset] = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }
}
