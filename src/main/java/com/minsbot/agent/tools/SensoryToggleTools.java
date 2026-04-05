package com.minsbot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Chat-driven toggles for the same features as the floating UI header: eye (watch), keyboard, ear, mouth.
 */
@Component
public class SensoryToggleTools {

    private static final Logger log = LoggerFactory.getLogger(SensoryToggleTools.class);

    private final ToolExecutionNotifier notifier;
    private final ScreenWatchingTools screenWatching;
    private final AudioListeningTools audioListening;
    private final TtsTools ttsTools;

    public SensoryToggleTools(ToolExecutionNotifier notifier,
                              ScreenWatchingTools screenWatching,
                              AudioListeningTools audioListening,
                              TtsTools ttsTools) {
        this.notifier = notifier;
        this.screenWatching = screenWatching;
        this.audioListening = audioListening;
        this.ttsTools = ttsTools;
    }

    @Tool(description = "Turn Mins Bot's eyes, keyboard, hearing, or mouth on/off from chat — same as the header buttons. "
            + "feature: eyes (or watch/vision) = screen watch; keyboard (or control/mouse) = keyboard+mouse permission; "
            + "hearing (or ears/listen) = live system-audio listening; mouth (or vocal) = speak translated audio in listen mode; "
            + "replies (or autospeak) = read normal chat replies aloud (auto_speak in config). "
            + "enabled true = on, false = off. "
            + "Use when the user says 'turn off your eyes', 'mute your voice', 'stop listening', 'enable keyboard', etc.")
    public String toggleMinsbotFeature(
            @ToolParam(description = "eyes | keyboard | hearing | mouth | replies (synonyms accepted)") String feature,
            @ToolParam(description = "true to turn on, false to turn off") boolean enabled) {
        if (feature == null || feature.isBlank()) {
            return "Say which feature: eyes, keyboard, hearing, mouth, or replies — and on or off.";
        }
        String f = normalizeFeature(feature.trim());
        if (f == null) {
            return "Unknown feature '" + feature.trim() + "'. Use: eyes, keyboard, hearing, mouth, or replies.";
        }
        notifier.notify((enabled ? "Enable " : "Disable ") + f);
        log.info("[SensoryToggle] {} → {}", f, enabled);

        return switch (f) {
            case "eyes" -> toggleEyes(enabled);
            case "keyboard" -> toggleKeyboard(enabled);
            case "hearing" -> toggleHearing(enabled);
            case "mouth" -> toggleMouth(enabled);
            case "replies" -> toggleReplies(enabled);
            default -> "Unknown feature.";
        };
    }

    @Tool(description = "Show whether screen watch (eyes), keyboard control, listening (ears), vocal mouth, and reply TTS are on or off.")
    public String getSensoryStatus() {
        notifier.notify("Sensory status");
        StringBuilder sb = new StringBuilder("Mins Bot sensory status (header buttons):\n");
        sb.append("- Eyes (screen watch): ").append(screenWatching.isWatching() ? "ON" : "OFF").append("\n");
        sb.append("- Keyboard (mouse/keys allowed): ").append(screenWatching.isControlEnabled() ? "ON" : "OFF").append("\n");
        sb.append("- Hearing (listen mode): ").append(audioListening.isListening() ? "ON" : "OFF").append("\n");
        sb.append("- Mouth (vocal translations): ").append(audioListening.isVocalMode() ? "ON" : "OFF").append("\n");
        sb.append("- Replies (read chat aloud): ").append(ttsTools.isAutoSpeak() ? "ON" : "OFF").append("\n");
        return sb.toString();
    }

    private static String normalizeFeature(String raw) {
        String lower = raw.toLowerCase().trim();
        String c = lower.replace(" ", "").replace("_", "").replace("-", "");

        if (c.equals("eyes") || c.equals("eye") || c.equals("watch") || c.equals("watching") || c.equals("vision") || c.equals("screen")) {
            return "eyes";
        }
        if (c.equals("keyboard") || c.equals("control") || c.equals("mouse") || c.equals("hands") || c.equals("keys")) {
            return "keyboard";
        }
        if (c.equals("hearing") || c.equals("ears") || c.equals("ear") || c.equals("listen") || c.equals("listening")) {
            return "hearing";
        }
        // Mouth = vocal translations in listen mode (header mouth button)
        if (c.equals("mouth") || c.equals("vocal")) {
            return "mouth";
        }
        // Replies = auto TTS for chat answers (## Voice auto_speak)
        if (c.equals("replies") || c.equals("autospeak") || c.equals("autospeech")
                || lower.contains("auto speak") || lower.contains("reply speech") || lower.contains("read replies")) {
            return "replies";
        }
        // Plain "voice" / "tts" usually means stop/start reading replies aloud
        if (c.equals("voice") || c.equals("tts")) {
            return "replies";
        }
        return null;
    }

    private String toggleEyes(boolean enabled) {
        if (enabled) {
            if (screenWatching.isWatching()) {
                return "Eyes (screen watch) are already ON.";
            }
            String r = screenWatching.startScreenWatch("observe the screen and help the user", "click");
            return "Eyes ON — " + r;
        }
        if (!screenWatching.isWatching()) {
            return "Eyes (screen watch) are already OFF.";
        }
        return "Eyes OFF — " + screenWatching.stopScreenWatch();
    }

    private String toggleKeyboard(boolean enabled) {
        screenWatching.setControlEnabled(enabled);
        return enabled
                ? "Keyboard/mouse control ON (bot may use Robot input when using PC control tools)."
                : "Keyboard/mouse control OFF.";
    }

    private String toggleHearing(boolean enabled) {
        if (enabled) {
            if (audioListening.isListening()) {
                return "Hearing (listen mode) is already ON.";
            }
            return "Hearing ON — " + audioListening.startListening();
        }
        if (!audioListening.isListening()) {
            return "Hearing (listen mode) is already OFF.";
        }
        return "Hearing OFF — " + audioListening.stopListening();
    }

    private String toggleMouth(boolean enabled) {
        audioListening.setVocalMode(enabled);
        return enabled
                ? "Mouth ON — translations in listen mode will be spoken aloud."
                : "Mouth OFF — vocal translations disabled.";
    }

    private String toggleReplies(boolean enabled) {
        ttsTools.setAutoSpeak(enabled);
        if (!enabled) {
            ttsTools.stopPlayback();
        }
        return enabled
                ? "Reply speech ON — bot will read normal chat replies aloud."
                : "Reply speech OFF — bot replies are text only (speak() tool still works if you ask).";
    }
}
