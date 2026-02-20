package com.minsbot.agent.tools;

import com.minsbot.ElevenLabsVoiceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Text-to-speech with audio caching. Uses ElevenLabs when configured, otherwise Windows SAPI.
 * Generated audio is cached to ~/mins_bot_data/tts_cache/ so repeated playback
 * (e.g. recurring TTS tasks) does not re-call the API.
 */
@Component
public class TtsTools {

    private static final Logger log = LoggerFactory.getLogger(TtsTools.class);

    private final ToolExecutionNotifier notifier;
    private final ElevenLabsVoiceService elevenLabs;

    /** In-memory cache: sanitized text key → WAV bytes. */
    private final ConcurrentHashMap<String, byte[]> audioCache = new ConcurrentHashMap<>();

    private Path cacheDir;

    public TtsTools(ToolExecutionNotifier notifier, ElevenLabsVoiceService elevenLabs) {
        this.notifier = notifier;
        this.elevenLabs = elevenLabs;
    }

    @PostConstruct
    public void init() throws IOException {
        cacheDir = Paths.get(System.getProperty("user.home"), "mins_bot_data", "tts_cache");
        Files.createDirectories(cacheDir);
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
    }

    // ═══ AI-callable tools ═══

    @Tool(description = "Read text aloud using text-to-speech (ElevenLabs when configured, otherwise Windows SAPI). "
            + "Uses caching — the same text is only generated once, then played from cache. "
            + "Use when the user asks to 'read aloud', 'speak', or 'say' something.")
    public String speak(
            @ToolParam(description = "Text to speak aloud") String text) {
        if (text == null || text.isBlank()) return "No text to speak.";
        notifier.notify("Speaking: " + (text.length() > 30 ? text.substring(0, 30) + "..." : text));

        String key = cacheKey(text);

        // Try cache first
        byte[] cached = getFromCache(key);
        if (cached != null) {
            playAudioBytes(cached);
            return "Spoke (cached): \"" + truncate(text) + "\"";
        }

        // Generate fresh audio
        if (elevenLabs.isEnabled()) {
            byte[] audio = generateElevenLabs(text);
            if (audio != null) {
                saveToCache(key, audio);
                playAudioBytes(audio);
                return "Spoke: \"" + truncate(text) + "\"";
            }
        }

        // Windows SAPI fallback — generates WAV file to cache
        return speakWindowsSapiCached(text, key);
    }

    // ═══ Public methods for recurring TTS ═══

    /**
     * Generate and cache audio for the given text without playing it.
     * Returns true if audio was successfully cached.
     */
    public boolean generateAndCache(String text) {
        if (text == null || text.isBlank()) return false;
        String key = cacheKey(text);
        if (audioCache.containsKey(key)) return true; // Already cached

        if (elevenLabs.isEnabled()) {
            byte[] audio = generateElevenLabs(text);
            if (audio != null) {
                saveToCache(key, audio);
                return true;
            }
        }

        // SAPI: generate WAV file
        return generateSapiWav(text, key);
    }

    /**
     * Play previously cached audio for the given text.
     * Returns true if played successfully, false if not in cache.
     */
    public boolean playFromCache(String text) {
        if (text == null || text.isBlank()) return false;
        String key = cacheKey(text);
        byte[] cached = getFromCache(key);
        if (cached != null) {
            playAudioBytes(cached);
            return true;
        }
        return false;
    }

    // ═══ Cache operations ═══

    private byte[] getFromCache(String key) {
        byte[] mem = audioCache.get(key);
        if (mem != null) return mem;

        // Check file system
        Path file = cacheDir.resolve(key + ".wav");
        if (Files.exists(file)) {
            try {
                byte[] bytes = Files.readAllBytes(file);
                audioCache.put(key, bytes);
                return bytes;
            } catch (IOException e) {
                log.warn("Failed to read cached TTS file: {}", file);
            }
        }
        return null;
    }

    private void saveToCache(String key, byte[] audio) {
        audioCache.put(key, audio);
        try {
            Files.write(cacheDir.resolve(key + ".wav"), audio);
            log.debug("Cached TTS audio: {}.wav ({} bytes)", key, audio.length);
        } catch (IOException e) {
            log.warn("Failed to save TTS cache file: {}", e.getMessage());
        }
    }

    /**
     * Cache key: sanitized, lowercase, max 50 chars, alphanumeric + underscores.
     */
    private String cacheKey(String text) {
        String sanitized = text.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (sanitized.length() > 50) sanitized = sanitized.substring(0, 50);
        if (sanitized.isEmpty()) sanitized = "tts_" + text.hashCode();
        return sanitized;
    }

    // ═══ Audio generation ═══

    private byte[] generateElevenLabs(String text) {
        try {
            byte[] audio = elevenLabs.textToSpeech(text);
            if (audio != null && audio.length > 0) return audio;
        } catch (Exception e) {
            log.warn("ElevenLabs TTS failed: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Windows SAPI: generate WAV to cache file, then load into memory.
     */
    private String speakWindowsSapiCached(String text, String key) {
        if (!isWindows()) {
            return "TTS requires Windows (SAPI) or ElevenLabs API key.";
        }
        try {
            Path wavFile = cacheDir.resolve(key + ".wav");
            if (!Files.exists(wavFile)) {
                if (!generateSapiWav(text, key)) {
                    return "Failed to generate SAPI audio.";
                }
            }
            byte[] audio = Files.readAllBytes(wavFile);
            audioCache.put(key, audio);
            playAudioBytes(audio);
            return "Spoke: \"" + truncate(text) + "\"";
        } catch (Exception e) {
            return "TTS failed: " + e.getMessage();
        }
    }

    private boolean generateSapiWav(String text, String key) {
        if (!isWindows()) return false;
        try {
            Path wavFile = cacheDir.resolve(key + ".wav");
            String escaped = text.replace("'", "''").replace("\r", " ").replace("\n", " ");
            if (escaped.length() > 4000) escaped = escaped.substring(0, 4000) + "...";
            String ps = "Add-Type -AssemblyName System.Speech; "
                    + "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; "
                    + "$s.SetOutputToWaveFile('" + wavFile.toString().replace("'", "''") + "'); "
                    + "$s.Speak('" + escaped + "'); "
                    + "$s.Dispose()";
            Path script = Files.createTempFile("mins_bot_tts_", ".ps1");
            Files.writeString(script, ps, StandardCharsets.UTF_8);
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString())
                    .redirectErrorStream(true)
                    .start();
            boolean done = p.waitFor(120, TimeUnit.SECONDS);
            Files.deleteIfExists(script);
            if (!done) {
                p.destroyForcibly();
                return false;
            }
            return Files.exists(wavFile) && Files.size(wavFile) > 0;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("SAPI WAV generation failed: {}", e.getMessage());
            return false;
        }
    }

    // ═══ Audio playback ═══

    private void playAudioBytes(byte[] audio) {
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(new ByteArrayInputStream(audio))) {
            Clip clip = AudioSystem.getClip();
            clip.open(ais);
            clip.start();
            while (clip.isRunning()) {
                Thread.sleep(50);
            }
            clip.close();
        } catch (Exception e) {
            log.warn("Audio playback failed: {}", e.getMessage());
        }
    }

    // ═══ Helpers ═══

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String truncate(String text) {
        return text.length() > 80 ? text.substring(0, 80) + "..." : text;
    }
}
