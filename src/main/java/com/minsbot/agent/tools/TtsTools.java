package com.minsbot.agent.tools;

import com.minsbot.ElevenLabsConfig;
import com.minsbot.ElevenLabsVoiceService;
import com.minsbot.FishAudioConfig;
import com.minsbot.FishAudioVoiceService;
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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
    private final ElevenLabsConfig.ElevenLabsProperties elevenLabsProps;
    private final FishAudioVoiceService fishAudio;
    private final FishAudioConfig.FishAudioProperties fishAudioProps;

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

    /** True if audio is currently playing (streaming or cached clip). */
    public boolean isSpeaking() { return activeLine != null || activeClip != null; }

    /** Tracks whether the first TTS attempt has been made (for startup fallback notice). */
    private volatile boolean startupTtsChecked = false;
    /** Set once if Fish Audio fails on the very first TTS attempt and we fall back. */
    private volatile String startupFallbackNotice = null;

    /** Returns (and clears) the startup fallback notice, or null if none. */
    public String getAndClearStartupNotice() {
        String notice = startupFallbackNotice;
        startupFallbackNotice = null;
        return notice;
    }

    // Config (mutable, reloaded at runtime)
    private volatile boolean autoSpeak = true;
    /** TTS engine preference: "elevenlabs", "openai", or "auto" (try both). */
    private volatile String ttsEngine = "auto";

    /** Ordered TTS priority (first = try first). Default: fishaudio, elevenlabs, openai. */
    private final CopyOnWriteArrayList<String> ttsPriority =
            new CopyOnWriteArrayList<>(List.of("fishaudio", "elevenlabs", "openai"));
    /** Per-engine enabled state (UI toggle). Default all true. */
    private final ConcurrentHashMap<String, Boolean> engineEnabled = new ConcurrentHashMap<>(Map.of(
            "fishaudio", true, "elevenlabs", true, "openai", true));

    public TtsTools(ToolExecutionNotifier notifier, ElevenLabsVoiceService elevenLabs,
                    OpenAiTtsService openAiTts, ElevenLabsConfig.ElevenLabsProperties elevenLabsProps,
                    FishAudioVoiceService fishAudio, FishAudioConfig.FishAudioProperties fishAudioProps) {
        this.notifier = notifier;
        this.elevenLabs = elevenLabs;
        this.openAiTts = openAiTts;
        this.elevenLabsProps = elevenLabsProps;
        this.fishAudio = fishAudio;
        this.fishAudioProps = fishAudioProps;
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
        log.info("[TTS] Ready — engine={}, autoSpeak={}, openAi={}, elevenLabs={}, fishAudio={}, voice={}",
                ttsEngine, autoSpeak, openAiTts.isAvailable(), elevenLabs.isEnabled(), fishAudio.isEnabled(), openAiTts.getVoice());
    }

    // ═══ Config ═══

    public String getTtsEngine() { return ttsEngine; }

    public boolean isAutoSpeak() { return autoSpeak; }

    /**
     * Turn automatic speech for chat replies on/off (persists {@code auto_speak} under ## Voice in minsbot_config.txt).
     */
    public void setAutoSpeak(boolean enabled) {
        autoSpeak = enabled;
        persistAutoSpeakToConfig(enabled);
        log.info("[TTS] Auto-speak {}", enabled ? "ON" : "OFF");
    }

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

    // ═══ Priority & per-engine enabled (for TTS Settings UI) ═══

    public List<String> getTtsPriority() { return List.copyOf(ttsPriority); }

    public void setTtsPriority(List<String> order) {
        ttsPriority.clear();
        ttsPriority.addAll(order);
        // First enabled engine becomes the active tts_engine
        for (String eng : order) {
            if (engineEnabled.getOrDefault(eng, true)) {
                ttsEngine = eng;
                break;
            }
        }
        persistConfigValue("tts_priority", String.join(",", order));
        persistTtsEngineToConfig(ttsEngine);
        log.info("[TTS] Priority updated: {} — active engine: {}", order, ttsEngine);
    }

    public boolean isEngineEnabled(String engine) {
        return engineEnabled.getOrDefault(engine, true);
    }

    public void setEngineEnabled(String engine, boolean enabled) {
        engineEnabled.put(engine, enabled);
        persistConfigValue(engine + "_enabled", String.valueOf(enabled));
        // If active engine was disabled, switch to next enabled
        if (!enabled && engine.equals(ttsEngine)) {
            for (String eng : ttsPriority) {
                if (engineEnabled.getOrDefault(eng, true)) {
                    ttsEngine = eng;
                    persistTtsEngineToConfig(eng);
                    log.info("[TTS] Active engine switched to {} (previous was disabled)", eng);
                    break;
                }
            }
        }
    }

    /** Switch engine without persisting (used for test playback). */
    public void setTtsEngineQuiet(String engine) { this.ttsEngine = engine; }

    /** Persist an arbitrary key under ## Voice in minsbot_config.txt. */
    public void persistConfigValue(String key, String value) {
        try {
            if (!Files.exists(CONFIG_PATH)) return;
            String content = Files.readString(CONFIG_PATH);
            String pattern = "(?m)(^- " + key + ":).*$";
            if (content.matches("(?s).*^- " + key + ":.*$.*")) {
                String updated = content.replaceAll(pattern, "$1 " + value);
                if (!updated.equals(content)) Files.writeString(CONFIG_PATH, updated);
            } else {
                // Insert new key at end of ## Voice section
                String updated = content.replaceFirst(
                        "(?m)(## Voice[^\n]*\n(?:- [^\n]*\n)*)",
                        "$1- " + key + ": " + value + "\n");
                if (!updated.equals(content)) Files.writeString(CONFIG_PATH, updated);
            }
        } catch (IOException e) {
            log.warn("[TTS] Failed to persist {}={}: {}", key, value, e.getMessage());
        }
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
                        if (val.equals("elevenlabs") || val.equals("openai") || val.equals("fishaudio") || val.equals("auto")) {
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
                    case "tts_priority" -> {
                        String raw = kv.substring(colon + 1).trim();
                        if (!raw.isBlank()) {
                            List<String> order = Arrays.asList(raw.split("\\s*,\\s*"));
                            ttsPriority.clear();
                            ttsPriority.addAll(order);
                        }
                    }
                    case "fishaudio_enabled" -> engineEnabled.put("fishaudio", val.equals("true"));
                    case "elevenlabs_enabled" -> engineEnabled.put("elevenlabs", val.equals("true"));
                    case "openai_enabled" -> engineEnabled.put("openai", val.equals("true"));
                    case "fishaudio_ref" -> {
                        String v = kv.substring(colon + 1).trim();
                        if (!v.isBlank()) fishAudioProps.setReferenceId(v);
                    }
                    case "elevenlabs_vid" -> {
                        String v = kv.substring(colon + 1).trim();
                        if (!v.isBlank()) elevenLabsProps.setVoiceId(v);
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

    @Tool(description = "Switch between Fish Audio and ElevenLabs as the active cloud TTS provider. "
            + "Use when the user asks to use Fish vs ElevenLabs, toggle voice providers, or 'switch TTS to fish/elevenlabs'. "
            + "Saves to minsbot_config.txt under ## Voice. For OpenAI-only or automatic engine order, use switchTtsEngine.")
    public String switchCloudTtsProvider(
            @ToolParam(description = "fish / fishaudio / 'fish audio' for Fish Audio; eleven / elevenlabs / 'eleven labs' for ElevenLabs") String provider) {
        if (provider == null || provider.isBlank()) {
            return "Say which provider: Fish Audio (fish) or ElevenLabs (eleven / elevenlabs).";
        }
        String eng = normalizeCloudTtsProvider(provider);
        if (eng == null) {
            return "Unknown provider '" + provider.trim() + "'. Use 'fish' for Fish Audio or 'eleven' / 'elevenlabs' for ElevenLabs.";
        }
        notifier.notify("Switching TTS to " + eng);
        return applyTtsEngineSwitch(eng);
    }

    @Tool(description = "Switch the active TTS (text-to-speech) engine. Use when the user says 'switch audio to ...', "
            + "'use fish audio', 'change voice to elevenlabs', 'switch tts to openai', etc. "
            + "Valid engines: 'fishaudio', 'elevenlabs', 'openai', 'auto'. "
            + "This changes the engine immediately AND saves it to minsbot_config.txt so it persists across restarts.")
    public String switchTtsEngine(
            @ToolParam(description = "Engine name: fishaudio, elevenlabs, openai, or auto") String engine) {
        if (engine == null || engine.isBlank()) return "Please specify an engine: fishaudio, elevenlabs, openai, or auto.";
        String eng = engine.trim().toLowerCase()
                .replace("fish audio", "fishaudio")
                .replace("fish_audio", "fishaudio")
                .replace("eleven labs", "elevenlabs")
                .replace("eleven_labs", "elevenlabs")
                .replace("openai tts", "openai");
        if (!eng.equals("fishaudio") && !eng.equals("elevenlabs") && !eng.equals("openai") && !eng.equals("auto")) {
            return "Unknown engine '" + engine + "'. Valid options: fishaudio, elevenlabs, openai, auto.";
        }
        notifier.notify("Switching TTS to " + eng);
        return applyTtsEngineSwitch(eng);
    }

    /**
     * Maps user phrasing to {@code fishaudio} or {@code elevenlabs}, or null if not recognized.
     */
    private static String normalizeCloudTtsProvider(String provider) {
        String s = provider.trim().toLowerCase()
                .replace("fish audio", "fishaudio")
                .replace("fish_audio", "fishaudio")
                .replace("eleven labs", "elevenlabs")
                .replace("eleven_labs", "elevenlabs");
        if (s.equals("fish") || s.equals("fishaudio")) return "fishaudio";
        if (s.equals("eleven") || s.equals("elevenlabs")) return "elevenlabs";
        return null;
    }

    /** Apply engine id (fishaudio, elevenlabs, openai, auto): warnings, state, persist, log. */
    private String applyTtsEngineSwitch(String eng) {
        String warning = "";
        if ("fishaudio".equals(eng) && !fishAudio.isEnabled()) {
            warning = " (Warning: Fish Audio API key not configured — it may not work until you set fish.audio.api.key)";
        } else if ("elevenlabs".equals(eng) && !elevenLabs.isEnabled()) {
            warning = " (Warning: ElevenLabs not fully configured — check API key and voice ID)";
        } else if ("openai".equals(eng) && !openAiTts.isAvailable()) {
            warning = " (Warning: OpenAI TTS not available — check API key)";
        }

        String oldEngine = ttsEngine;
        ttsEngine = eng;
        persistTtsEngineToConfig(eng);
        log.info("[TTS] Engine switched: {} → {}", oldEngine, eng);

        String label = switch (eng) {
            case "fishaudio" -> "Fish Audio";
            case "elevenlabs" -> "ElevenLabs";
            case "openai" -> "OpenAI TTS";
            default -> "Auto (best available)";
        };
        return "TTS engine switched to " + label + "." + warning;
    }

    @Tool(description = "Show current TTS status: which engine is active, which engines are available, and current settings. "
            + "Use when the user asks 'what voice are you using?', 'which tts?', 'voice status', etc.")
    public String getTtsStatus() {
        notifier.notify("Checking TTS status");
        StringBuilder sb = new StringBuilder("TTS Status:\n");
        sb.append("- Active engine: ").append(ttsEngine).append("\n");
        sb.append("- Auto-speak: ").append(autoSpeak ? "ON" : "OFF").append("\n\n");
        sb.append("Available engines:\n");
        sb.append("  1. Fish Audio: ").append(fishAudio.isEnabled() ? "READY" : "not configured").append("\n");
        sb.append("  2. ElevenLabs: ").append(elevenLabs.isEnabled() ? "READY" : "not configured").append("\n");
        sb.append("  3. OpenAI TTS: ").append(openAiTts.isAvailable() ? "READY" : "not configured").append("\n\n");
        sb.append("To switch Fish ↔ ElevenLabs: switchCloudTtsProvider. For OpenAI or auto: switchTtsEngine.");
        return sb.toString();
    }

    /**
     * Persist the tts_engine value to ~/mins_bot_data/minsbot_config.txt under ## Voice.
     */
    private void persistTtsEngineToConfig(String engine) {
        try {
            if (!Files.exists(CONFIG_PATH)) return;
            String content = Files.readString(CONFIG_PATH);
            // Replace the tts_engine line under ## Voice
            String updated = content.replaceAll(
                    "(?m)(^- tts_engine:).*$",
                    "$1 " + engine);
            if (!updated.equals(content)) {
                Files.writeString(CONFIG_PATH, updated);
                log.debug("[TTS] Persisted tts_engine={} to config", engine);
            }
        } catch (IOException e) {
            log.warn("[TTS] Failed to persist engine to config: {}", e.getMessage());
        }
    }

    private void persistAutoSpeakToConfig(boolean enabled) {
        try {
            if (!Files.exists(CONFIG_PATH)) return;
            String content = Files.readString(CONFIG_PATH);
            String val = enabled ? "true" : "false";
            String updated = content.replaceAll("(?m)(^- auto_speak:).*$", "$1 " + val);
            if (!updated.equals(content)) {
                Files.writeString(CONFIG_PATH, updated);
                log.debug("[TTS] Persisted auto_speak={} to config", val);
            }
        } catch (IOException e) {
            log.warn("[TTS] Failed to persist auto_speak to config: {}", e.getMessage());
        }
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

    /**
     * Speak text with a gender-matched ElevenLabs voice on a background thread.
     * Used by vocal/mouth mode to speak translations aloud with matching voice.
     *
     * @param text   text to speak
     * @param gender "male", "female", or anything else (defaults to male)
     */
    public void speakAsyncWithVoice(String text, String gender) {
        if (text == null || text.isBlank()) return;
        String clean = cleanForSpeech(text);
        if (clean.isBlank()) return;
        ttsExecutor.submit(() -> {
            try {
                stopRequested = false;
                doSpeakWithVoice(clean, gender);
            } catch (Exception e) {
                log.debug("[TTS] Async speak-with-voice failed: {}", e.getMessage());
            }
        });
    }

    private void doSpeakWithVoice(String text, String gender) {
        // Resolve gendered voice ID from ElevenLabs config
        String voiceId = "female".equalsIgnoreCase(gender)
                ? elevenLabsProps.getFemaleVoiceId()
                : elevenLabsProps.getMaleVoiceId();

        String genderSegment = text + "_" + gender;

        // Try ElevenLabs with the gendered voice
        if (elevenLabs.isEnabled() && voiceId != null && !voiceId.isBlank()) {
            log.info("[TTS] Speaking with {} voice (voiceId={})", gender, voiceId);
            InputStream stream = elevenLabs.textToSpeechStream(text, voiceId);
            if (streamAndPlay(stream, cacheKeyForProvider(genderSegment, "elevenlabs"), text, "ElevenLabs-" + gender)) {
                return;
            }
        }

        // Try Fish Audio with gendered voice
        String fishRefId = "female".equalsIgnoreCase(gender)
                ? fishAudioProps.getFemaleReferenceId()
                : fishAudioProps.getMaleReferenceId();
        if (fishAudio.isEnabled() && fishRefId != null && !fishRefId.isBlank()) {
            log.info("[TTS] Speaking with {} voice via FishAudio (ref={})", gender, fishRefId);
            InputStream fishStream = fishAudio.textToSpeechStream(text, fishRefId);
            if (streamAndPlay(fishStream, cacheKeyForProvider(genderSegment, "fishaudio"), text, "FishAudio-" + gender)) {
                return;
            }
        }

        // Fallback: OpenAI TTS with gender-mapped voice
        if (openAiTts.isAvailable()) {
            String origVoice = openAiTts.getVoice();
            try {
                openAiTts.setVoice("female".equalsIgnoreCase(gender) ? "nova" : "onyx");
                if (tryStreamOpenAi(text, cacheKeyForProvider(genderSegment, "openai"))) return;
            } finally {
                openAiTts.setVoice(origVoice);
            }
        }

        log.warn("[TTS] Gender-matched speak failed — no engine available");
    }

    // ═══ Core speak logic ═══

    private String doSpeak(String text) {
        stopRequested = false; // reset for this new speak call
        String engine = ttsEngine;
        log.info("[TTS] doSpeak engine={}, fish.enabled={}, elevenLabs.enabled={}, openAi.available={}",
                engine, fishAudio.isEnabled(), elevenLabs.isEnabled(), openAiTts.isAvailable());

        String cachedMsg = "Spoke (cached): \"" + truncate(text) + "\"";
        String spokeMsg = "Spoke: \"" + truncate(text) + "\"";

        if ("elevenlabs".equals(engine)) {
            if (tryPlayCachedProvider(text, "elevenlabs")) return cachedMsg;
            if (tryStreamElevenLabs(text, cacheKeyForProvider(text, "elevenlabs"))) return spokeMsg;
            if (tryStreamFishAudio(text, cacheKeyForProvider(text, "fishaudio"))) return spokeMsg;
            if (tryStreamOpenAi(text, cacheKeyForProvider(text, "openai"))) return spokeMsg;
        } else if ("fishaudio".equals(engine)) {
            if (tryPlayCachedProvider(text, "fishaudio")) { startupTtsChecked = true; return cachedMsg; }
            if (tryStreamFishAudio(text, cacheKeyForProvider(text, "fishaudio"))) { startupTtsChecked = true; return spokeMsg; }
            log.info("[TTS] Fish Audio failed or empty — falling back to ElevenLabs / OpenAI");
            if (!startupTtsChecked) {
                startupFallbackNotice = "Fish Audio is not responding — falling back to ElevenLabs for TTS.";
                log.warn("[TTS] Startup notice: Fish Audio unavailable, falling back");
            }
            startupTtsChecked = true;
            if (tryStreamElevenLabs(text, cacheKeyForProvider(text, "elevenlabs"))) return spokeMsg;
            if (tryStreamOpenAi(text, cacheKeyForProvider(text, "openai"))) return spokeMsg;
        } else if ("openai".equals(engine)) {
            if (tryPlayCachedProvider(text, "openai")) return cachedMsg;
            if (tryStreamOpenAi(text, cacheKeyForProvider(text, "openai"))) return spokeMsg;
            if (tryStreamFishAudio(text, cacheKeyForProvider(text, "fishaudio"))) return spokeMsg;
            if (tryStreamElevenLabs(text, cacheKeyForProvider(text, "elevenlabs"))) return spokeMsg;
        } else {
            // "auto" — follow user-configured priority order + enabled state
            if (tryPlayCachedPriorityOrder(text)) return cachedMsg;
            for (String eng : ttsPriority) {
                if (!engineEnabled.getOrDefault(eng, true)) continue;
                if (tryStreamByEngine(eng, text)) return spokeMsg;
            }
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

    private boolean tryStreamFishAudio(String text, String key) {
        if (!fishAudio.isEnabled()) {
            log.debug("[TTS] FishAudio skipped — not enabled");
            return false;
        }
        log.info("[TTS] Trying FishAudio stream (ref={})...", fishAudio.getReferenceId());
        boolean ok = streamAndPlay(fishAudio.textToSpeechStream(text), key, text, "FishAudio");
        if (!ok) log.warn("[TTS] FishAudio stream returned no data");
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
        if (audioCache.containsKey(cacheKeyForProvider(text, "openai"))
                || audioCache.containsKey(cacheKeyForProvider(text, "fishaudio"))
                || audioCache.containsKey(cacheKeyForProvider(text, "elevenlabs"))) {
            return true;
        }

        if (openAiTts.isAvailable()) {
            byte[] audio = generateOpenAiTts(text);
            if (audio != null) { saveToCache(cacheKeyForProvider(text, "openai"), audio); return true; }
        }
        if (fishAudio.isEnabled()) {
            byte[] audio = generateFishAudio(text);
            if (audio != null) { saveToCache(cacheKeyForProvider(text, "fishaudio"), audio); return true; }
        }
        if (elevenLabs.isEnabled()) {
            byte[] audio = generateElevenLabs(text);
            if (audio != null) { saveToCache(cacheKeyForProvider(text, "elevenlabs"), audio); return true; }
        }
        return false;
    }

    public boolean playFromCache(String text) {
        if (text == null || text.isBlank()) return false;
        return tryPlayCachedAutoOrder(text);
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

    /**
     * Separate cache entries per TTS provider so switching Fish ↔ ElevenLabs does not replay
     * the wrong engine's WAV (keys were previously text-only).
     *
     * @param providerTag {@code fishaudio}, {@code elevenlabs}, or {@code openai}
     */
    private String cacheKeyForProvider(String text, String providerTag) {
        return cacheKey(text + "_" + providerTag);
    }

    private boolean tryPlayCachedProvider(String text, String providerTag) {
        String k = cacheKeyForProvider(text, providerTag);
        if (audioCache.containsKey(k) || Files.exists(cacheDir.resolve(k + ".wav"))) {
            playAudio(k);
            return true;
        }
        return false;
    }

    /** Same priority as {@code auto} generation: Fish, then ElevenLabs, then OpenAI. */
    private boolean tryPlayCachedAutoOrder(String text) {
        if (tryPlayCachedProvider(text, "fishaudio")) return true;
        if (tryPlayCachedProvider(text, "elevenlabs")) return true;
        if (tryPlayCachedProvider(text, "openai")) return true;
        return false;
    }

    /** Try cached audio in user-configured priority order, respecting enabled state. */
    private boolean tryPlayCachedPriorityOrder(String text) {
        for (String eng : ttsPriority) {
            if (!engineEnabled.getOrDefault(eng, true)) continue;
            if (tryPlayCachedProvider(text, eng)) return true;
        }
        return false;
    }

    /** Try streaming from a specific engine by name. */
    private boolean tryStreamByEngine(String engine, String text) {
        return switch (engine) {
            case "fishaudio" -> tryStreamFishAudio(text, cacheKeyForProvider(text, "fishaudio"));
            case "elevenlabs" -> tryStreamElevenLabs(text, cacheKeyForProvider(text, "elevenlabs"));
            case "openai" -> tryStreamOpenAi(text, cacheKeyForProvider(text, "openai"));
            default -> false;
        };
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

    private byte[] generateFishAudio(String text) {
        try {
            byte[] audio = fishAudio.textToSpeech(text);
            if (audio != null && audio.length > 0) {
                // Fish Audio PCM: wrap as WAV (24kHz 16-bit mono)
                byte[] wav = wrapPcmAsWav(audio, 24000, 1, 16);
                log.info("[TTS] FishAudio generated {} bytes WAV", wav.length);
                return wav;
            }
        } catch (Exception e) {
            log.warn("[TTS] FishAudio failed: {}", e.getMessage());
        }
        return null;
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
