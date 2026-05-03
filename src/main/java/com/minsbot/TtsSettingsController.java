package com.minsbot;

import com.minsbot.agent.OpenAiTtsService;
import com.minsbot.agent.tools.TtsTools;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST endpoints for the TTS Settings UI tab.
 * Reads/writes the ## Voice section in ~/mins_bot_data/minsbot_config.txt.
 */
@RestController
@RequestMapping("/api/tts")
public class TtsSettingsController {

    private final TtsTools ttsTools;
    private final FishAudioConfig.FishAudioProperties fishProps;
    private final ElevenLabsConfig.ElevenLabsProperties elevenProps;
    private final FishAudioVoiceService fishAudio;
    private final ElevenLabsVoiceService elevenLabs;
    private final OpenAiTtsService openAiTts;
    private final LocalTtsService localTts;

    public TtsSettingsController(TtsTools ttsTools,
                                  FishAudioConfig.FishAudioProperties fishProps,
                                  ElevenLabsConfig.ElevenLabsProperties elevenProps,
                                  FishAudioVoiceService fishAudio,
                                  ElevenLabsVoiceService elevenLabs,
                                  OpenAiTtsService openAiTts,
                                  LocalTtsService localTts) {
        this.ttsTools = ttsTools;
        this.fishProps = fishProps;
        this.elevenProps = elevenProps;
        this.fishAudio = fishAudio;
        this.elevenLabs = elevenLabs;
        this.openAiTts = openAiTts;
        this.localTts = localTts;
    }

    /** Installed local (Piper) voices + currently selected one. */
    @GetMapping(value = "/local-voices", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> localVoices() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("voices", localTts.listInstalledVoices());
        out.put("selected", localTts.getSelectedVoice());
        return out;
    }

    /** Pick which Piper voice to use. Body: {"filename":"en_US-amy-medium.onnx"}. */
    @PostMapping(value = "/local-voices/select", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> selectLocalVoice(@RequestBody Map<String, Object> body) {
        String filename = body == null ? null : (String) body.get("filename");
        if (filename == null || filename.isBlank()) {
            return Map.of("success", false, "message", "Missing filename");
        }
        boolean ok = localTts.setSelectedVoice(filename);
        return Map.of("success", ok, "selected", localTts.getSelectedVoice());
    }

    /** Return full TTS configuration for the settings UI. */
    @GetMapping(value = "/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getConfig() {
        Map<String, Object> cfg = new LinkedHashMap<>();

        // Priority order
        cfg.put("priority", ttsTools.getTtsPriority());

        // Enabled state per engine
        Map<String, Boolean> enabled = new LinkedHashMap<>();
        enabled.put("piper", ttsTools.isEngineEnabled("piper"));
        enabled.put("fishaudio", ttsTools.isEngineEnabled("fishaudio"));
        enabled.put("elevenlabs", ttsTools.isEngineEnabled("elevenlabs"));
        enabled.put("openai", ttsTools.isEngineEnabled("openai"));
        cfg.put("enabled", enabled);

        // Availability — Piper is ready when the binary is installed and a voice is selected.
        Map<String, Boolean> available = new LinkedHashMap<>();
        available.put("piper", localTts.isEnabled());
        available.put("fishaudio", fishAudio.isEnabled());
        available.put("elevenlabs", elevenLabs.isEnabled());
        available.put("openai", openAiTts.isAvailable());
        cfg.put("available", available);

        // Voice settings per engine
        Map<String, Object> voices = new LinkedHashMap<>();

        Map<String, Object> fishVoice = new LinkedHashMap<>();
        fishVoice.put("referenceId", fishProps.getReferenceId());
        fishVoice.put("model", fishProps.getModel());
        fishVoice.put("speed", fishProps.getProsodySpeed());
        voices.put("fishaudio", fishVoice);

        Map<String, Object> elevenVoice = new LinkedHashMap<>();
        elevenVoice.put("voiceId", elevenProps.getVoiceId());
        elevenVoice.put("modelId", elevenProps.getModelId());
        voices.put("elevenlabs", elevenVoice);

        Map<String, Object> openaiVoice = new LinkedHashMap<>();
        openaiVoice.put("voice", openAiTts.getVoice());
        openaiVoice.put("speed", openAiTts.getSpeed());
        voices.put("openai", openaiVoice);

        cfg.put("voices", voices);

        // Auto-speak
        cfg.put("autoSpeak", ttsTools.isAutoSpeak());

        return cfg;
    }

    /** Save TTS priority order. */
    @SuppressWarnings("unchecked")
    @PostMapping(value = "/priority", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> savePriority(@RequestBody Map<String, Object> body) {
        List<String> priority = (List<String>) body.get("priority");
        if (priority != null) {
            ttsTools.setTtsPriority(priority);
        }
        return Map.of("success", true, "priority", ttsTools.getTtsPriority());
    }

    /** Save TTS engine enabled states. */
    @SuppressWarnings("unchecked")
    @PostMapping(value = "/enabled", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> saveEnabled(@RequestBody Map<String, Object> body) {
        Map<String, Object> input = (Map<String, Object>) body.get("enabled");
        if (input != null) {
            for (var entry : input.entrySet()) {
                ttsTools.setEngineEnabled(entry.getKey(), Boolean.valueOf(entry.getValue().toString()));
            }
        }
        return Map.of("success", true);
    }

    /** Save voice config for a specific engine. */
    @SuppressWarnings("unchecked")
    @PostMapping(value = "/voice", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> saveVoice(@RequestBody Map<String, Object> body) {
        String engine = (String) body.get("engine");
        if (engine == null) return Map.of("success", false, "message", "Missing engine");

        switch (engine) {
            case "fishaudio" -> {
                String ref = (String) body.get("referenceId");
                if (ref != null) ttsTools.persistConfigValue("fishaudio_ref", ref);
                Object speed = body.get("speed");
                if (speed != null) ttsTools.persistConfigValue("fishaudio_speed", speed.toString());
            }
            case "elevenlabs" -> {
                String vid = (String) body.get("voiceId");
                if (vid != null) ttsTools.persistConfigValue("elevenlabs_vid", vid);
            }
            case "openai" -> {
                String voice = (String) body.get("voice");
                if (voice != null) {
                    openAiTts.setVoice(voice);
                    ttsTools.persistConfigValue("voice", voice);
                }
                Object speed = body.get("speed");
                if (speed != null) {
                    try {
                        openAiTts.setSpeed(Double.parseDouble(speed.toString()));
                        ttsTools.persistConfigValue("speed", speed.toString());
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        return Map.of("success", true);
    }

    /** Toggle auto-speak. */
    @PostMapping(value = "/auto-speak", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> setAutoSpeak(@RequestBody Map<String, Object> body) {
        Object val = body.get("enabled");
        if (val != null) {
            ttsTools.setAutoSpeak(Boolean.valueOf(val.toString()));
        }
        return Map.of("success", true, "autoSpeak", ttsTools.isAutoSpeak());
    }

    /** Lightweight live state — currently just whether audio is actively playing.
     *  Polled by Sentry Mode to flip the gear into the "speaking" pulse. */
    @GetMapping(value = "/state", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> ttsState() {
        return Map.of("speaking", ttsTools.isSpeaking());
    }

    /** Get current local-Piper rate + pitch settings. */
    @GetMapping(value = "/local-rates", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getLocalRates() {
        return Map.of(
                "lengthScale", localTts.getLengthScale(),
                "narrationLengthScale", localTts.getNarrationLengthScale(),
                "pitchSemitones", localTts.getPitchSemitones());
    }

    /** Update local-Piper rate + pitch. Any of the three fields are optional. */
    @PostMapping(value = "/local-rates", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> setLocalRates(@RequestBody Map<String, Object> body) {
        if (body.get("lengthScale") instanceof Number n) localTts.setLengthScale(n.doubleValue());
        if (body.get("narrationLengthScale") instanceof Number n) localTts.setNarrationLengthScale(n.doubleValue());
        if (body.get("pitchSemitones") instanceof Number n) localTts.setPitchSemitones(n.doubleValue());
        return getLocalRates();
    }

    /** Returns (and clears) a one-time startup notice if Fish Audio failed on first TTS attempt. */
    @GetMapping(value = "/startup-notice", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> startupNotice() {
        String notice = ttsTools.getAndClearStartupNotice();
        return Map.of("notice", notice != null ? notice : "");
    }

    /** Test TTS — speak a short sample text with the given engine. */
    @PostMapping(value = "/test", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> testTts(@RequestBody Map<String, Object> body) {
        String engine = (String) body.get("engine");
        String text = (String) body.getOrDefault("text", "Hello, this is a test of the text to speech system.");
        if (engine == null) return Map.of("success", false, "message", "Missing engine");

        String oldEngine = ttsTools.getTtsEngine();
        try {
            // Temporarily switch engine for the test
            ttsTools.setTtsEngineQuiet(engine);
            String result = ttsTools.speak(text);
            return Map.of("success", true, "message", result);
        } finally {
            ttsTools.setTtsEngineQuiet(oldEngine);
        }
    }
}
