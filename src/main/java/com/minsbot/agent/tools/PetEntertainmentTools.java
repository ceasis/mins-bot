package com.minsbot.agent.tools;

import com.minsbot.skills.petentertainment.PetEntertainmentConfig;
import com.minsbot.skills.petentertainment.PetEntertainmentService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Lets the chat agent keep cats / dogs entertained — opens curated YouTube
 * enrichment content, or generates and narrates a calming bedtime story
 * tailored to the pet via the existing TTS pipeline.
 */
@Component
public class PetEntertainmentTools {

    @Autowired(required = false) private PetEntertainmentService svc;
    @Autowired(required = false) private PetEntertainmentConfig.PetEntertainmentProperties props;
    @Autowired(required = false) private TtsTools ttsTools;
    @Autowired(required = false) @Qualifier("chatClient") private ChatClient chatClient;
    @Autowired(required = false) private ToolExecutionNotifier notifier;

    @Tool(description = "Open a long-form YouTube video to entertain a cat or dog. Use when the user "
            + "says 'keep my cat busy', 'play something for my dog', 'cat tv', 'calm music for my "
            + "dog', 'birds for my cat', 'separation anxiety music for dog', 'dog tv', 'fish tank "
            + "for cat'. Picks the right preset from the pet type + activity, then opens the YouTube "
            + "search results in the default browser so the user can pick the longest video.")
    public String entertainPet(
            @ToolParam(description = "Free-text describing the pet + activity, e.g. 'birds for my cat', 'calming music for my anxious dog', 'dog tv', 'fish tank'. Or one of the preset keys: cat-birds, cat-fish, cat-mice, cat-relax, cat-asmr, dog-calm, dog-separation, dog-tv, dog-sleep, dog-puppy, nature, rain, fireplace.") String request) {
        if (svc == null || props == null) return "petentertainment skill not loaded.";
        if (!props.isEnabled()) return "petentertainment skill is disabled. Set app.skills.petentertainment.enabled=true.";
        if (notifier != null) notifier.notify("🐾 finding pet-friendly content...");
        try {
            Map<String, Object> r = svc.play(request);
            return "🐾 opened in browser — preset: " + r.get("preset")
                    + "\n  search: " + r.get("query")
                    + "\n  url: " + r.get("openedUrl")
                    + "\n  tip: " + r.get("tip");
        } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "Show the available pet-entertainment presets. Use when the user says "
            + "'what content do you have for pets', 'list pet videos', 'options for my cat'.")
    public String listPetPresets() {
        if (svc == null || props == null || !props.isEnabled()) return "petentertainment skill is disabled.";
        Map<String, Object> r = svc.listPresets();
        StringBuilder sb = new StringBuilder("🐾 Pet enrichment presets:\n");
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> list = (java.util.List<Map<String, Object>>) r.get("presets");
        for (Map<String, Object> p : list) sb.append("  • ").append(p.get("preset")).append(" — ").append(p.get("youtubeQuery")).append("\n");
        return sb.toString();
    }

    @Tool(description = "Generate AND narrate a calm bedtime story for a cat or dog. Use when the "
            + "user says 'tell my dog a story', 'narrate a story for my cat', 'bedtime story for my "
            + "pet', 'put my dog to sleep with a story'. The story is tuned for low stimulation: "
            + "slow rhythm, soft imagery, repetitive phrasing, ends with the pet drifting to sleep. "
            + "It's spoken aloud through the bot's TTS — best paired with the JARVIS voice preset "
            + "for a calm tone.")
    public String narratePetStory(
            @ToolParam(description = "Pet type, e.g. 'cat', 'dog', 'puppy', 'kitten'") String petType,
            @ToolParam(description = "Approximate length in minutes. 5 is a good default. 10-15 for sleep.", required = false) Integer minutes) {
        if (svc == null || props == null) return "petentertainment skill not loaded.";
        if (!props.isEnabled()) return "petentertainment skill is disabled.";
        if (chatClient == null) return "Chat client not available — can't generate story.";
        if (ttsTools == null) return "TTS not available — story would have no audio.";
        int m = minutes == null ? 5 : minutes;
        if (notifier != null) notifier.notify("🐾 writing a " + m + "-minute story for your " + petType + "...");
        try {
            Map<String, Object> p = svc.storyPrompt(petType, m);
            String story = chatClient.prompt().user((String) p.get("llmPrompt")).call().content();
            if (story == null || story.isBlank()) return "Story generation returned empty.";
            ttsTools.speakNarrationAsync(story);
            int chars = story.length();
            return "🐾 narrating a " + m + "-minute calm story for your " + petType
                    + " (" + chars + " chars). It will play through the bot's voice."
                    + "\n\nStory preview:\n" + (chars > 200 ? story.substring(0, 200) + "..." : story);
        } catch (Exception e) {
            return "Failed: " + e.getMessage();
        }
    }
}
