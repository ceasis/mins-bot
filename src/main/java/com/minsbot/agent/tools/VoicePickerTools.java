package com.minsbot.agent.tools;

import com.minsbot.LocalTtsService;
import com.minsbot.firstrun.PiperInstallerService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Lets the chat agent change the offline (Piper) voice. Maps natural-language
 * descriptions ("low male british", "female american") to specific Piper
 * voice models, downloads them on demand from rhasspy/piper-voices on
 * HuggingFace, and switches to them.
 */
@Component
public class VoicePickerTools {

    @Autowired(required = false) private LocalTtsService localTts;
    @Autowired(required = false) private PiperInstallerService piper;
    @Autowired(required = false) private ToolExecutionNotifier notifier;

    /** Curated voice catalog: friendly description → (HF subpath, filename, label, optional pitch shift in semitones). */
    private static final Map<String, String[]> CATALOG = new LinkedHashMap<>();
    static {
        // JARVIS preset — Alan medium with -2 semitones pitch shift for that baritone butler tone
        CATALOG.put("jarvis",                   new String[]{"en/en_GB/alan/medium",               "en_GB-alan-medium.onnx",               "JARVIS-style — Alan + pitch −2 semitones (baritone, measured cadence)", "-2"});
        // British male
        CATALOG.put("british-male-low",         new String[]{"en/en_GB/alan/low",                  "en_GB-alan-low.onnx",                  "Alan — British male, lower quality (smaller, deeper sounding)"});
        CATALOG.put("british-male",             new String[]{"en/en_GB/alan/medium",               "en_GB-alan-medium.onnx",               "Alan — British male, medium quality (recommended)"});
        CATALOG.put("british-male-northern",    new String[]{"en/en_GB/northern_english_male/medium","en_GB-northern_english_male-medium.onnx","Northern English male"});
        // British female
        CATALOG.put("british-female",           new String[]{"en/en_GB/jenny_dioco/medium",        "en_GB-jenny_dioco-medium.onnx",        "Jenny — British female"});
        CATALOG.put("british-female-southern",  new String[]{"en/en_GB/southern_english_female/low","en_GB-southern_english_female-low.onnx","Southern English female"});
        // American male
        CATALOG.put("american-male",            new String[]{"en/en_US/ryan/high",                 "en_US-ryan-high.onnx",                 "Ryan — American male, high quality"});
        CATALOG.put("american-male-deep",       new String[]{"en/en_US/joe/medium",                "en_US-joe-medium.onnx",                "Joe — American male, deeper tone"});
        // American female
        CATALOG.put("american-female",          new String[]{"en/en_US/amy/medium",                "en_US-amy-medium.onnx",                "Amy — American female (default)"});
        CATALOG.put("american-female-libritts", new String[]{"en/en_US/libritts_r/medium",         "en_US-libritts_r-medium.onnx",         "LibriTTS-R — American female"});
    }

    @Tool(description = "Change the bot's offline voice. Use when the user says 'change your voice', "
            + "'use a british male voice', 'sound like a low male british', 'switch to female "
            + "american voice', 'set voice to <description>'. Auto-downloads the matching Piper "
            + "voice model from HuggingFace if not already installed, then switches to it. "
            + "Available presets: british-male, british-male-low, british-male-northern, "
            + "british-female, american-male, american-male-deep, american-female. The skill "
            + "infers the closest preset from any natural-language description.")
    public String setVoice(
            @ToolParam(description = "Voice description: 'low male british', 'british male', "
                    + "'female american', 'deep male', 'jenny', or one of the preset keys "
                    + "(british-male, american-male, etc.)") String description) {
        if (localTts == null || piper == null) return "Local TTS not loaded.";
        if (!piper.isInstalled()) return "Piper isn't installed yet. Open the Models tab and install Piper first.";

        String preset = match(description);
        if (preset == null) return "Couldn't match '" + description + "' to a preset. Available: " + CATALOG.keySet();
        String[] entry = CATALOG.get(preset);
        String hfPath = entry[0], filename = entry[1], label = entry[2];

        if (notifier != null) notifier.notify("🎙 switching voice → " + label + "...");
        try {
            if (!piper.hasVoice(filename)) {
                piper.installVoice(hfPath, filename, line -> {
                    if (notifier != null && line != null && line.startsWith("log|"))
                        notifier.notify("🎙 " + line.substring(4));
                });
            }
            boolean ok = localTts.setSelectedVoice(filename);
            if (!ok) return "✗ Downloaded but couldn't switch — voice file may be missing sidecar JSON.";
            // Apply preset pitch if specified, otherwise reset to 0
            double pitch = entry.length > 3 ? Double.parseDouble(entry[3]) : 0.0;
            localTts.setPitchSemitones(pitch);
            String pitchInfo = pitch == 0.0 ? "" : "\n  pitch: " + pitch + " semitones";
            return "🎙 voice set to " + label + "\n  preset: " + preset + "\n  file: " + filename + pitchInfo;
        } catch (Exception e) {
            return "Failed to install/switch voice: " + e.getMessage();
        }
    }

    @Tool(description = "List the offline voice presets the bot can switch to. Use when the user "
            + "says 'what voices do you have', 'list available voices', 'what voice options'.")
    public String listVoices() {
        if (localTts == null || piper == null) return "Local TTS not loaded.";
        StringBuilder sb = new StringBuilder("🎙 Voice presets:\n");
        String current = localTts.getSelectedVoice();
        for (var e : CATALOG.entrySet()) {
            String[] v = e.getValue();
            boolean installed = piper.hasVoice(v[1]);
            boolean active = v[1].equals(current);
            sb.append("  ").append(active ? "▶" : " ").append(" ")
                    .append(e.getKey()).append(installed ? " ✓" : " ⬇")
                    .append(" — ").append(v[2]).append("\n");
        }
        sb.append("\n✓ = installed, ⬇ = needs download");
        if (current != null) sb.append("\nCurrent: ").append(current);
        return sb.toString();
    }

    @Tool(description = "Show which voice the bot is currently using. Use when the user says "
            + "'what voice is this', 'what's your current voice', 'which voice are you using'.")
    public String currentVoice() {
        if (localTts == null) return "Local TTS not loaded.";
        String v = localTts.getSelectedVoice();
        if (v == null) return "No voice selected. Say 'use a british male voice' to install + switch.";
        double pitch = localTts.getPitchSemitones();
        String suffix = pitch == 0.0 ? "" : " · pitch " + pitch + " semitones";
        for (var e : CATALOG.entrySet())
            if (v.equals(e.getValue()[1])) return "🎙 Current: " + e.getValue()[2] + " (" + v + ")" + suffix;
        return "🎙 Current: " + v + suffix;
    }

    @Tool(description = "Set the bot's overall speech rate. 1.0 = native (default). "
            + ">1 = slower / more measured. <1 = faster. Use when the user says 'speak slower', "
            + "'talk faster', 'slow down your voice', 'set speech rate'. Range 0.5–3.0. Persists "
            + "across restarts. Note: narration (stories, recitations) already auto-uses 1.35.")
    public String setSpeechRate(@ToolParam(description = "Length-scale. 1.0=natural, 1.3=slower, 0.85=faster") double scale) {
        if (localTts == null) return "Local TTS not loaded.";
        localTts.setLengthScale(scale);
        return "🎙 speech rate set — length-scale: " + localTts.getLengthScale()
                + " (" + (localTts.getLengthScale() > 1.0 ? "slower" : localTts.getLengthScale() < 1.0 ? "faster" : "natural") + ")";
    }

    @Tool(description = "Lower or raise the bot's voice pitch by N semitones. Use when the user "
            + "says 'lower your voice', 'make your voice deeper', 'raise pitch by 1', 'reset pitch'. "
            + "Negative = lower (deeper). Positive = higher. Range −12 to +12. Side effect: changing "
            + "pitch via this method also slightly changes speech tempo (slower for lower pitch, "
            + "which sounds more measured/JARVIS-like).")
    public String setPitch(@ToolParam(description = "Semitones to shift. -2 = JARVIS baritone. 0 = natural.") double semitones) {
        if (localTts == null) return "Local TTS not loaded.";
        localTts.setPitchSemitones(semitones);
        return "🎙 pitch set to " + localTts.getPitchSemitones() + " semitones";
    }

    /** Loose mapping from a natural-language description to a preset key. */
    private static String match(String desc) {
        if (desc == null) return null;
        String d = desc.toLowerCase(Locale.ROOT);
        // Exact preset key match
        if (CATALOG.containsKey(d)) return d;
        // JARVIS-style requests
        if (d.contains("jarvis") || d.contains("iron man") || d.contains("butler") || d.contains("paul bettany")) return "jarvis";
        // Specific name mentions
        if (d.contains("alan")) return "british-male";
        if (d.contains("jenny")) return "british-female";
        if (d.contains("ryan")) return "american-male";
        if (d.contains("amy")) return "american-female";
        if (d.contains("joe")) return "american-male-deep";
        // Adjective parsing
        boolean british = d.contains("british") || d.contains("uk") || d.contains("english") || d.contains("london") || d.contains("rp");
        boolean american = d.contains("american") || d.contains("us ") || d.contains("usa");
        boolean male = d.contains("male") && !d.contains("female");
        boolean female = d.contains("female") || d.contains("woman");
        boolean deep = d.contains("low") || d.contains("deep") || d.contains("bass");
        boolean northern = d.contains("northern") || d.contains("yorkshire") || d.contains("manchester");
        boolean southern = d.contains("southern") || d.contains("posh");

        if (british && male && deep) return "british-male-low";
        if (british && male && northern) return "british-male-northern";
        if (british && male) return "british-male";
        if (british && female && southern) return "british-female-southern";
        if (british && female) return "british-female";
        if (american && male && deep) return "american-male-deep";
        if (american && male) return "american-male";
        if (american && female) return "american-female";
        // Fallbacks based on single dimensions
        if (deep && male) return "american-male-deep";
        if (british) return "british-male";
        if (american) return "american-female";
        if (male) return "british-male";
        if (female) return "american-female";
        return null;
    }
}
