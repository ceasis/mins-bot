package com.minsbot.agent.tools;

import com.minsbot.ElevenLabsVoiceService;
import com.minsbot.agent.OpenAiTtsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import javax.sound.sampled.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Text-to-speech with audio caching. Priority: OpenAI TTS → ElevenLabs.
 * Generated audio is cached to ~/mins_bot_data/tts_cache/ so repeated playback
 * does not re-call the API.
 *
 * <p>Auto-speak: when enabled, every bot reply is spoken automatically via {@link #speakAsync(String)}.
 * Config is in ~/mins_bot_data/minsbot_config.txt under "## Voice".
 */
@Component
public class TtsTools {

    private static final Logger log = LoggerFactory.getLogger(TtsTools.class);

    private static final Path CONFIG_PATH =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "minsbot_config.txt");

    private final ToolExecutionNotifier notifier;
    private final ElevenLabsVoiceService elevenLabs;
    private final OpenAiTtsService openAiTts;

    /** In-memory cache: sanitized text key → WAV bytes. */
    private final ConcurrentHashMap<String, byte[]> audioCache = new ConcurrentHashMap<>();

    /** Single-threaded executor for async playback (auto-speak). */
    private final ExecutorService ttsExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tts-async");
        t.setDaemon(true);
        return t;
    });

    private Path cacheDir;

    /** Set to true to interrupt current playback (checked in streaming/playback loops). */
    private volatile boolean stopRequested = false;
    /** Reference to the currently playing SourceDataLine (streaming) — null when idle. */
    private volatile SourceDataLine activeLine = null;
    /** Reference to the currently playing Clip (cached playback) — null when idle. */
    private volatile Clip activeClip = null;

    // Config (mutable, reloaded at runtime)
    private volatile boolean autoSpeak = true;
    /** TTS engine preference: "elevenlabs", "openai", or "auto" (try both). */
    private volatile String ttsEngine = "auto";

    public TtsTools(ToolExecutionNotifier notifier, ElevenLabsVoiceService elevenLabs,
                    OpenAiTtsService openAiTts) {
        this.notifier = notifier;
        this.elevenLabs = elevenLabs;
        this.openAiTts = openAiTts;
    }

    @PostConstruct
    public void init() throws IOException {
        cacheDir = Paths.get(System.getProperty("user.home"), "mins_bot_data", "tts_cache");
        Files.createDirectories(cacheDir);
        loadConfigFromFile();

        // Clear old SAPI cache when OpenAI TTS becomes available (one-time migration)
        Path marker = cacheDir.resolve(".openai_tts_active");
        if (openAiTts.isAvailable() && !Files.exists(marker)) {
            try (DirectoryStream<Path> old = Files.newDirectoryStream(cacheDir, "*.wav")) {
                int cleared = 0;
                for (Path f : old) { Files.deleteIfExists(f); cleared++; }
                if (cleared > 0) log.info("[TTS] Cleared {} old cache files (switching to OpenAI TTS)", cleared);
            }
            Files.createFile(marker);
        }

        // Pre-load existing cached WAV files into memory
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(cacheDir, "*.wav")) {
            int loaded = 0;
            for (Path file : stream) {
                try {
                    String key = file.getFileName().toString().replace(".wav", "");
                    byte[] bytes = Files.readAllBytes(file);
                    audioCache.put(key, bytes);
                    loaded++;
                } catch (IOException e) {
                    log.warn("Could not load cached TTS file: {}", file.getFileName());
                }
            }
            if (loaded > 0) log.info("Loaded {} cached TTS audio files from {}", loaded, cacheDir);
        }
        log.info("[TTS] Ready — engine={}, autoSpeak={}, openAi={}, elevenLabs={}, voice={}",
                ttsEngine, autoSpeak, openAiTts.isAvailable(), elevenLabs.isEnabled(), openAiTts.getVoice());
    }

    // ═══ Config ═══

    public boolean isAutoSpeak() { return autoSpeak; }

    /**
     * Stop any currently playing TTS audio immediately.
     * Called by ChatService when a new user message arrives so the old reply
     * stops speaking and the bot can respond to the new input.
     */
    public void stopPlayback() {
        stopRequested = true;
        // Stop streaming playback
        SourceDataLine line = activeLine;
        if (line != null) {
            try { line.stop(); line.flush(); line.close(); } catch (Exception ignored) {}
        }
        // Stop cached clip playback
        Clip clip = activeClip;
        if (clip != null) {
            try { clip.stop(); clip.close(); } catch (Exception ignored) {}
        }
        log.debug("[TTS] Playback stop requested");
    }

    public void reloadConfig() {
        loadConfigFromFile();
        log.info("[TTS] Config reloaded — engine={}, autoSpeak={}, voice={}, speed={}",
                ttsEngine, autoSpeak, openAiTts.getVoice(), openAiTts.getSpeed());
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
                if (!currentSection.equals("## voice")) continue;
                if (!trimmed.startsWith("- ")) continue;

                String kv = trimmed.substring(2).trim();
                int colon = kv.indexOf(':');
                if (colon < 0) continue;
                String key = kv.substring(0, colon).trim().toLowerCase();
                String val = kv.substring(colon + 1).trim().toLowerCase();

                switch (key) {
                    case "auto_speak" -> autoSpeak = val.equals("true");
                    case "tts_engine" -> {
                        if (val.equals("elevenlabs") || val.equals("openai") || val.equals("auto")) {
                            ttsEngine = val;
                        }
                    }
                    case "voice" -> {
                        String v = kv.substring(colon + 1).trim(); // preserve case
                        if (!v.isBlank()) openAiTts.setVoice(v);
                    }
                    case "speed" -> {
                        try { openAiTts.setSpeed(Double.parseDouble(val)); }
                        catch (NumberFormatException ignored) {}
                    }
                }
            }
        } catch (IOException e) {
            log.warn("[TTS] Could not read config: {}", e.getMessage());
        }
    }

    // ═══ AI-callable tools ═══

    @Tool(description = "Convert text to speech and play it as audio. The user will hear the spoken text. "
            + "Use this whenever the user asks to 'say' something, 'speak', 'read aloud', or 'say something' — you must call this tool so they hear audio, not just see text. "
            + "Pass the exact phrase to speak (e.g. speak('Hello!') for 'say hello'). Uses OpenAI TTS or ElevenLabs; same text is cached for replay.")
    public String speak(
            @ToolParam(description = "Text to speak aloud") String text) {
        if (text == null || text.isBlank()) return "No text to speak.";
        notifier.notify("Speaking: " + (text.length() > 30 ? text.substring(0, 30) + "..." : text));
        return doSpeak(text);
    }

    @Tool(description = "List all available microphone devices on this PC. "
            + "Use when the user has mic issues (e.g. AirPods connected but can't hear them, wrong mic). "
            + "Show the list and suggest setting mic_device in minsbot_config.txt under ## Voice.")
    public String listMicrophones() {
        notifier.notify("Listing microphones");
        java.util.List<String> mics = com.minsbot.NativeVoiceService.listMicrophones();
        if (mics.isEmpty()) return "No microphone devices found.";
        StringBuilder sb = new StringBuilder("Available microphones:\n");
        for (int i = 0; i < mics.size(); i++) {
            sb.append(i + 1).append(". ").append(mics.get(i)).append("\n");
        }
        sb.append("\nTo use a specific mic, set 'mic_device' in minsbot_config.txt under ## Voice.");
        return sb.toString();
    }

    // ═══ Auto-speak (called by ChatService after every reply) ═══

    /**
     * Speak text on a background thread. Non-blocking — returns immediately.
     * Used for auto-speak of bot replies.
     */
    public void speakAsync(String text) {
        if (text == null || text.isBlank()) return;
        // Strip markdown/formatting for cleaner speech
        String clean = cleanForSpeech(text);
        if (clean.isBlank()) return;
        ttsExecutor.submit(() -> {
            try {
                doSpeak(clean);
            } catch (Exception e) {
                log.debug("[TTS] Async speak failed: {}", e.getMessage());
            }
        });
    }

    // ═══ Core speak logic ═══

    private String doSpeak(String text) {
        stopRequested = false; // reset for this new speak call
        String key = cacheKey(text);

        // Try cache first (instant playback)
        if (audioCache.containsKey(key) || Files.exists(cacheDir.resolve(key + ".wav"))) {
            playAudio(key);
            return "Spoke (cached): \"" + truncate(text) + "\"";
        }

        // Stream and play based on tts_engine preference
        String engine = ttsEngine;
        log.info("[TTS] doSpeak engine={}, elevenLabs.enabled={}, openAi.available={}",
                engine, elevenLabs.isEnabled(), openAiTts.isAvailable());

        if ("elevenlabs".equals(engine)) {
            // ElevenLabs first, OpenAI fallback
            if (tryStreamElevenLabs(text, key)) return "Spoke: \"" + truncate(text) + "\"";
            if (tryStreamOpenAi(text, key))     return "Spoke: \"" + truncate(text) + "\"";
        } else if ("openai".equals(engine)) {
            // OpenAI first, ElevenLabs fallback
            if (tryStreamOpenAi(text, key))     return "Spoke: \"" + truncate(text) + "\"";
            if (tryStreamElevenLabs(text, key)) return "Spoke: \"" + truncate(text) + "\"";
        } else {
            // "auto" — try whichever is available (ElevenLabs first for quality)
            if (elevenLabs.isEnabled() && tryStreamElevenLabs(text, key)) return "Spoke: \"" + truncate(text) + "\"";
            if (openAiTts.isAvailable() && tryStreamOpenAi(text, key))   return "Spoke: \"" + truncate(text) + "\"";
        }

        log.warn("[TTS] All cloud TTS engines failed — no audio produced");
        return "TTS failed: no cloud engine available. Configure OpenAI or ElevenLabs API key.";
    }

    private boolean tryStreamElevenLabs(String text, String key) {
        if (!elevenLabs.isEnabled()) {
            log.debug("[TTS] ElevenLabs skipped — not enabled (enabled={}, hasKey={}, hasVoice={})",
                    elevenLabs.isEnabled(),
                    elevenLabs.getVoiceId() != null && !elevenLabs.getVoiceId().isBlank(),
                    elevenLabs.getModelId());
            return false;
        }
        log.info("[TTS] Trying ElevenLabs stream (voice={})...", elevenLabs.getVoiceId());
        boolean ok = streamAndPlay(elevenLabs.textToSpeechStream(text), key, text, "ElevenLabs");
        if (!ok) log.warn("[TTS] ElevenLabs stream returned no data");
        return ok;
    }

    private boolean tryStreamOpenAi(String text, String key) {
        if (!openAiTts.isAvailable()) {
            log.debug("[TTS] OpenAI TTS skipped — not available");
            return false;
        }
        log.info("[TTS] Trying OpenAI stream (voice={})...", openAiTts.getVoice());
        boolean ok = streamAndPlay(openAiTts.textToSpeechStream(text), key, text, "OpenAI");
        if (!ok) log.warn("[TTS] OpenAI stream returned no data");
        return ok;
    }

    /**
     * Stream raw PCM (24kHz 16-bit mono) from any source and play via SourceDataLine
     * in real-time. Audio starts as soon as the first chunk arrives (~300ms).
     * Caches the complete audio as WAV after playback finishes.
     */
    private boolean streamAndPlay(InputStream pcmStream, String key, String text, String engineName) {
        if (pcmStream == null) return false;
        try (pcmStream) {
            // 24kHz 16-bit mono signed little-endian PCM
            AudioFormat fmt = new AudioFormat(24000, 16, 1, true, false);
            SourceDataLine line = AudioSystem.getSourceDataLine(fmt);
            line.open(fmt, 16384); // 16KB buffer (~340ms)
            line.start();
            activeLine = line;

            ByteArrayOutputStream collector = new ByteArrayOutputStream();
            byte[] buf = new byte[4096]; // ~85ms of audio per chunk
            int frameSize = fmt.getFrameSize(); // 2 bytes for 16-bit mono
            int leftover = 0; // leftover byte from previous read
            byte savedByte = 0;
            int read;
            boolean interrupted = false;
            while ((read = pcmStream.read(buf)) != -1) {
                if (stopRequested) { interrupted = true; break; }
                int offset = 0;
                int available = read;
                // Prepend leftover byte from previous iteration
                if (leftover > 0 && available > 0) {
                    byte[] pair = { savedByte, buf[0] };
                    line.write(pair, 0, 2);
                    collector.write(pair, 0, 2);
                    offset = 1;
                    available = read - 1;
                    leftover = 0;
                }
                // Write only complete frames (multiples of frameSize)
                int aligned = (available / frameSize) * frameSize;
                if (aligned > 0) {
                    line.write(buf, offset, aligned);
                    collector.write(buf, offset, aligned);
                }
                // Save any trailing odd byte for next iteration
                if (available % frameSize != 0) {
                    savedByte = buf[offset + aligned];
                    leftover = 1;
                }
            }

            if (interrupted) {
                line.stop();
                line.flush();
                log.info("[TTS] {} playback interrupted by new user input", engineName);
            } else {
                line.drain(); // wait for remaining buffered audio to finish
            }
            line.close();
            activeLine = null;

            // Cache the complete audio as WAV for instant replay next time
            byte[] pcm = collector.toByteArray();
            if (pcm.length > 0) {
                byte[] wav = wrapPcmAsWav(pcm, 24000, 1, 16);
                saveToCache(key, wav);
                log.info("[TTS] {} streamed {} bytes for: {}", engineName, wav.length,
                        text.length() > 40 ? text.substring(0, 40) + "..." : text);
            }
            return pcm.length > 0;

        } catch (Exception e) {
            activeLine = null;
            log.warn("[TTS] {} stream failed: {}", engineName, e.getMessage());
            return false;
        }
    }

    // ═══ Public methods for recurring TTS ═══

    public boolean generateAndCache(String text) {
        if (text == null || text.isBlank()) return false;
        String key = cacheKey(text);
        if (audioCache.containsKey(key)) return true;

        if (openAiTts.isAvailable()) {
            byte[] audio = generateOpenAiTts(text);
            if (audio != null) { saveToCache(key, audio); return true; }
        }
        if (elevenLabs.isEnabled()) {
            byte[] audio = generateElevenLabs(text);
            if (audio != null) { saveToCache(key, audio); return true; }
        }
        return false;
    }

    public boolean playFromCache(String text) {
        if (text == null || text.isBlank()) return false;
        String key = cacheKey(text);
        Path wavFile = cacheDir.resolve(key + ".wav");
        if (Files.exists(wavFile)) {
            playAudio(key);
            return true;
        }
        return false;
    }

    // ═══ Cache operations ═══

    private void saveToCache(String key, byte[] audio) {
        audioCache.put(key, audio);
        try {
            Files.write(cacheDir.resolve(key + ".wav"), audio);
            log.debug("Cached TTS audio: {}.wav ({} bytes)", key, audio.length);
        } catch (IOException e) {
            log.warn("Failed to save TTS cache file: {}", e.getMessage());
        }
    }

    private String cacheKey(String text) {
        String sanitized = text.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (sanitized.length() > 50) sanitized = sanitized.substring(0, 50);
        if (sanitized.isEmpty()) sanitized = "tts_" + text.hashCode();
        return sanitized;
    }

    // ═══ Audio generation ═══

    private byte[] generateOpenAiTts(String text) {
        try {
            byte[] pcm = openAiTts.textToSpeech(text);
            if (pcm == null || pcm.length == 0) {
                log.info("[TTS] OpenAI TTS returned no data for: {}",
                        text.length() > 40 ? text.substring(0, 40) + "..." : text);
                return null;
            }
            // Wrap raw PCM (24kHz 16-bit mono) with a proper WAV header — no ffmpeg needed
            byte[] wav = wrapPcmAsWav(pcm, 24000, 1, 16);
            log.info("[TTS] OpenAI TTS generated {} bytes WAV", wav.length);
            return wav;
        } catch (Exception e) {
            log.warn("[TTS] OpenAI TTS failed: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Wrap raw PCM bytes with a standard 44-byte RIFF/WAVE header.
     * OpenAI's "pcm" format returns 24000 Hz, 16-bit, mono, little-endian PCM with no headers.
     */
    private static byte[] wrapPcmAsWav(byte[] pcm, int sampleRate, int channels, int bitsPerSample) {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int dataSize = pcm.length;
        int chunkSize = 36 + dataSize;

        byte[] wav = new byte[44 + dataSize];
        // RIFF header
        wav[0] = 'R'; wav[1] = 'I'; wav[2] = 'F'; wav[3] = 'F';
        writeInt32LE(wav, 4, chunkSize);
        wav[8] = 'W'; wav[9] = 'A'; wav[10] = 'V'; wav[11] = 'E';
        // fmt sub-chunk
        wav[12] = 'f'; wav[13] = 'm'; wav[14] = 't'; wav[15] = ' ';
        writeInt32LE(wav, 16, 16);              // sub-chunk size (PCM = 16)
        writeInt16LE(wav, 20, (short) 1);       // audio format (PCM = 1)
        writeInt16LE(wav, 22, (short) channels);
        writeInt32LE(wav, 24, sampleRate);
        writeInt32LE(wav, 28, byteRate);
        writeInt16LE(wav, 32, (short) blockAlign);
        writeInt16LE(wav, 34, (short) bitsPerSample);
        // data sub-chunk
        wav[36] = 'd'; wav[37] = 'a'; wav[38] = 't'; wav[39] = 'a';
        writeInt32LE(wav, 40, dataSize);
        System.arraycopy(pcm, 0, wav, 44, dataSize);
        return wav;
    }

    private static void writeInt32LE(byte[] buf, int offset, int value) {
        buf[offset]     = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
        buf[offset + 2] = (byte) ((value >> 16) & 0xFF);
        buf[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static void writeInt16LE(byte[] buf, int offset, short value) {
        buf[offset]     = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    private byte[] generateElevenLabs(String text) {
        try {
            byte[] audio = elevenLabs.textToSpeech(text);
            if (audio != null && audio.length > 0) return audio;
        } catch (Exception e) {
            log.warn("ElevenLabs TTS failed: {}", e.getMessage());
        }
        return null;
    }

    // ═══ Audio playback ═══

    private void playAudio(String key) {
        Path wavFile = cacheDir.resolve(key + ".wav");
        if (!Files.exists(wavFile)) {
            log.warn("WAV file not found for playback: {}", wavFile);
            return;
        }

        // Primary: Java Sound API (instant — no process spawn)
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(wavFile.toFile())) {
            Clip clip = AudioSystem.getClip();
            clip.open(ais);
            activeClip = clip;
            CountDownLatch latch = new CountDownLatch(1);
            clip.addLineListener(event -> {
                if (event.getType() == LineEvent.Type.STOP) latch.countDown();
            });
            clip.start();
            latch.await(120, TimeUnit.SECONDS);
            clip.close();
            activeClip = null;
            return;
        } catch (Exception e) {
            activeClip = null;
            log.debug("[TTS] Java Sound playback failed, trying PowerShell: {}", e.getMessage());
        }

    }

    // ═══ Text cleaning ═══

    /**
     * Strip markdown formatting, URLs, code blocks, etc. for cleaner speech.
     */
    private static String cleanForSpeech(String text) {
        if (text == null) return "";
        String clean = text;
        // Remove code blocks
        clean = clean.replaceAll("```[\\s\\S]*?```", "");
        // Remove inline code
        clean = clean.replaceAll("`[^`]+`", "");
        // Remove markdown links — keep text: [text](url) → text
        clean = clean.replaceAll("\\[([^]]+)]\\([^)]+\\)", "$1");
        // Remove URLs
        clean = clean.replaceAll("https?://\\S+", "");
        // Remove markdown bold/italic
        clean = clean.replaceAll("\\*{1,3}([^*]+)\\*{1,3}", "$1");
        // Remove markdown headers
        clean = clean.replaceAll("(?m)^#{1,6}\\s+", "");
        // Collapse whitespace
        clean = clean.replaceAll("\\s+", " ").trim();
        // Truncate for TTS (OpenAI max ~4096 chars)
        if (clean.length() > 3000) clean = clean.substring(0, 3000);
        return clean;
    }

    // ═══ Helpers ═══

    private static String truncate(String text) {
        return text.length() > 80 ? text.substring(0, 80) + "..." : text;
    }
}
