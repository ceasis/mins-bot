package com.minsbot.skills.petentertainment;

import org.springframework.stereotype.Service;

import java.awt.Desktop;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Opens curated long-form YouTube content designed for pet enrichment.
 * Uses YouTube search URLs (rather than fixed video IDs) so links don't rot
 * when a specific video gets taken down — the top result is still relevant.
 *
 * Categories tuned to research on what actually engages cats/dogs:
 *   Cats — visual stimuli (birds, squirrels, fish, mice) + chittering audio
 *   Dogs — frequency-specific calming music ("Through a Dog's Ear" style),
 *          separation-anxiety playlists, dog-TV with other dogs
 */
@Service
public class PetEntertainmentService {

    private static final boolean WIN = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");

    private static final Map<String, String> PRESETS = new LinkedHashMap<>();
    static {
        // Cats
        PRESETS.put("cat-birds",     "cat tv birds squirrels 8 hours");
        PRESETS.put("cat-fish",      "fish tank for cats 8 hours 4k");
        PRESETS.put("cat-mice",      "mice for cats to watch 8 hours");
        PRESETS.put("cat-relax",     "music for cats anxiety calming");
        PRESETS.put("cat-asmr",      "asmr for cats purring sounds");
        // Dogs
        PRESETS.put("dog-calm",      "calming music for dogs anxiety 12 hours");
        PRESETS.put("dog-separation","music for dogs separation anxiety 8 hours");
        PRESETS.put("dog-tv",        "dog tv for dogs to watch when alone 8 hours");
        PRESETS.put("dog-sleep",     "sleep music for dogs deep relaxation");
        PRESETS.put("dog-puppy",     "puppy calming music 4 hours");
        // Both / generic
        PRESETS.put("nature",        "nature sounds 8 hours forest birds");
        PRESETS.put("rain",          "rain sounds 10 hours pet calming");
        PRESETS.put("fireplace",     "fireplace 8 hours crackling");
    }

    public Map<String, Object> play(String preset) throws Exception {
        if (preset == null || preset.isBlank()) preset = "cat-birds";
        String key = preset.toLowerCase(Locale.ROOT).trim();
        String query = PRESETS.get(key);
        if (query == null) {
            // Loose match: try to infer from keywords
            String inferred = match(key);
            if (inferred == null) throw new IllegalArgumentException("unknown preset '" + preset + "'. Available: " + PRESETS.keySet());
            key = inferred;
            query = PRESETS.get(key);
        }
        String url = "https://www.youtube.com/results?search_query="
                + URLEncoder.encode(query, StandardCharsets.UTF_8);
        openUrl(url);
        return Map.of("ok", true, "preset", key, "query", query, "openedUrl", url,
                "tip", "Pick the longest video result (8+ hours) for set-and-forget enrichment.");
    }

    public Map<String, Object> playCustom(String query) throws Exception {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("query required");
        String url = "https://www.youtube.com/results?search_query="
                + URLEncoder.encode(query, StandardCharsets.UTF_8);
        openUrl(url);
        return Map.of("ok", true, "query", query, "openedUrl", url);
    }

    public Map<String, Object> listPresets() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var e : PRESETS.entrySet()) out.add(Map.of("preset", e.getKey(), "youtubeQuery", e.getValue()));
        return Map.of("count", out.size(), "presets", out);
    }

    /** A calm, repetitive bedtime-style story prompt the bot can synthesize TTS for.
     *  Returns the prompt the chat agent should narrate (the calling layer feeds this
     *  into the LLM and pipes the result through TTS). */
    public Map<String, Object> storyPrompt(String petType, int approxMinutes) {
        String pet = (petType == null || petType.isBlank()) ? "pet" : petType.toLowerCase(Locale.ROOT);
        if (approxMinutes < 1) approxMinutes = 5;
        if (approxMinutes > 30) approxMinutes = 30;
        // Roughly 130 wpm for narration → ~130 words/minute target length
        int targetWords = approxMinutes * 130;
        String llmPrompt = "Write a calm, low-stimulation bedtime story specifically designed to relax a "
                + pet + ". Aim for approximately " + targetWords + " words (about " + approxMinutes
                + " minutes when narrated slowly). Rules:\n"
                + "- Soft, repetitive language with simple imagery (soft grass, gentle wind, slow water).\n"
                + "- No loud events, no sudden actions, no exclamation marks, no shouting.\n"
                + "- Slow rhythm: short sentences, frequent commas, repetitive phrasing.\n"
                + "- Mention the " + pet + " often (warm fur, sleepy eyes, slow breathing).\n"
                + "- End with the " + pet + " drifting peacefully to sleep.\n"
                + "Output ONLY the story text — no title, no preamble.";
        return Map.of("petType", pet, "approxMinutes", approxMinutes,
                "targetWords", targetWords, "llmPrompt", llmPrompt);
    }

    private static String match(String s) {
        if (s.contains("cat") && s.contains("bird")) return "cat-birds";
        if (s.contains("cat") && s.contains("fish")) return "cat-fish";
        if (s.contains("cat") && (s.contains("mouse") || s.contains("mice"))) return "cat-mice";
        if (s.contains("cat") && (s.contains("calm") || s.contains("relax") || s.contains("anxiety"))) return "cat-relax";
        if (s.contains("dog") && (s.contains("alone") || s.contains("separation"))) return "dog-separation";
        if (s.contains("dog") && (s.contains("calm") || s.contains("anxiety") || s.contains("relax"))) return "dog-calm";
        if (s.contains("dog") && (s.contains("watch") || s.contains("tv"))) return "dog-tv";
        if (s.contains("dog") && s.contains("sleep")) return "dog-sleep";
        if (s.contains("puppy")) return "dog-puppy";
        if (s.contains("cat")) return "cat-birds";
        if (s.contains("dog")) return "dog-calm";
        if (s.contains("rain")) return "rain";
        if (s.contains("fire")) return "fireplace";
        if (s.contains("nature") || s.contains("forest")) return "nature";
        return null;
    }

    private static void openUrl(String url) throws Exception {
        try { Desktop.getDesktop().browse(URI.create(url)); }
        catch (Exception e) {
            String[] cmd = WIN ? new String[]{"cmd", "/c", "start", "", url}
                    : MAC ? new String[]{"open", url}
                    : new String[]{"xdg-open", url};
            new ProcessBuilder(cmd).start();
        }
    }
}
