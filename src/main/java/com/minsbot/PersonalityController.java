package com.minsbot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * REST endpoints for the Personality tab.
 * Stores multiple personalities as JSON files in ~/mins_bot_data/personalities/.
 * The active personality ID is stored in ~/mins_bot_data/personality_active.txt.
 */
@RestController
@RequestMapping("/api/personality")
public class PersonalityController {

    private static final Logger log = LoggerFactory.getLogger(PersonalityController.class);
    private static final Path DATA_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "personalities");
    private static final Path ACTIVE_FILE =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "personality_active.txt");
    private static final ObjectMapper mapper = new ObjectMapper();

    // ─── GET /api/personality/list — all saved personalities + active id ───

    @GetMapping(value = "/list", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> list() {
        List<Map<String, Object>> items = new ArrayList<>();
        try {
            Files.createDirectories(DATA_DIR);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(DATA_DIR, "*.json")) {
                for (Path file : stream) {
                    try {
                        Map<String, Object> p = mapper.readValue(Files.readString(file),
                                new TypeReference<>() {});
                        items.add(p);
                    } catch (IOException e) {
                        log.warn("[Personality] Failed to read {}: {}", file.getFileName(), e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            log.warn("[Personality] Failed to list: {}", e.getMessage());
        }
        // Sort by name for consistent display
        items.sort(Comparator.comparing(p -> String.valueOf(p.getOrDefault("name", ""))));
        String activeId = loadActiveId();
        return Map.of("personalities", items, "activeId", activeId != null ? activeId : "");
    }

    // ─── GET /api/personality — load the active personality ───

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> load() {
        String activeId = loadActiveId();
        if (activeId != null && !activeId.isBlank()) {
            Map<String, Object> p = loadById(activeId);
            if (p != null) return p;
        }
        return defaultPersonality();
    }

    // ─── POST /api/personality — save a personality (creates or updates) ───

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> save(@RequestBody Map<String, Object> body) {
        try {
            Files.createDirectories(DATA_DIR);
            // Ensure it has an id
            String id = (String) body.get("id");
            if (id == null || id.isBlank()) {
                id = UUID.randomUUID().toString().substring(0, 8);
                body.put("id", id);
            }
            Path file = DATA_DIR.resolve(id + ".json");
            Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(body));
            return Map.of("success", true, "id", id);
        } catch (IOException e) {
            log.warn("[Personality] Failed to save: {}", e.getMessage());
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    // ─── POST /api/personality/activate — set the active personality ───

    @PostMapping(value = "/activate", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> activate(@RequestBody Map<String, Object> body) {
        String id = (String) body.get("id");
        if (id == null || id.isBlank()) return Map.of("success", false, "message", "Missing id");
        try {
            Files.createDirectories(ACTIVE_FILE.getParent());
            Files.writeString(ACTIVE_FILE, id);
            return Map.of("success", true, "activeId", id);
        } catch (IOException e) {
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    // ─── DELETE /api/personality/{id} — delete a personality ───

    @DeleteMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> delete(@PathVariable String id) {
        try {
            Path file = DATA_DIR.resolve(id + ".json");
            Files.deleteIfExists(file);
            // If it was the active one, clear active
            String activeId = loadActiveId();
            if (id.equals(activeId)) {
                Files.deleteIfExists(ACTIVE_FILE);
            }
            return Map.of("success", true);
        } catch (IOException e) {
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    // ─── POST /api/personality/randomize — generate, save, and return ───

    @PostMapping(value = "/randomize", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> randomize() {
        Map<String, Object> p = randomPersonality();
        String id = UUID.randomUUID().toString().substring(0, 8);
        p.put("id", id);
        try {
            Files.createDirectories(DATA_DIR);
            Path file = DATA_DIR.resolve(id + ".json");
            Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(p));
        } catch (IOException e) {
            log.warn("[Personality] Failed to save randomized: {}", e.getMessage());
        }
        return p;
    }

    // ─── Build system prompt fragment ───

    /**
     * Loads the active personality and converts it to a natural-language system prompt block.
     * Returns empty string if no personality is configured.
     */
    public String buildPromptFragment() {
        String activeId = loadActiveId();
        if (activeId == null || activeId.isBlank()) return "";
        Map<String, Object> p = loadById(activeId);
        if (p == null) return "";

        StringBuilder sb = new StringBuilder();

        // 1. Identity
        appendIfPresent(sb, p, "name", "Name/Alias: %s");
        appendIfPresent(sb, p, "role", "Role: %s");
        appendIfPresent(sb, p, "domain", "Domain expertise: %s");
        appendIfPresent(sb, p, "backstory", "Backstory: %s");

        // 2. Tone
        appendIfPresent(sb, p, "formality", "Formality: %s");
        appendIfPresent(sb, p, "verbosity", "Verbosity: %s");
        appendIfPresent(sb, p, "demeanor", "Demeanor: %s");
        appendIfPresent(sb, p, "humor", "Humor: %s");
        appendIfPresent(sb, p, "emojis", "Emoji usage: %s");

        // 3. Traits (sliders stored as numbers 1-5)
        appendSlider(sb, p, "assertiveness", "Passive", "Assertive");
        appendSlider(sb, p, "empathy", "Low empathy", "High empathy");
        appendSlider(sb, p, "curiosity", "Reactive", "Proactive");
        appendSlider(sb, p, "patience", "Low patience", "High patience");
        appendSlider(sb, p, "creativity", "Logical", "Imaginative");

        // 4. Decision-making
        appendIfPresent(sb, p, "decisionApproach", "Decision approach: %s");
        appendIfPresent(sb, p, "riskStyle", "Risk style: %s");
        appendIfPresent(sb, p, "reasoning", "Reasoning: %s");
        appendIfPresent(sb, p, "exploration", "Exploration: %s");

        // 5. Interaction
        appendIfPresent(sb, p, "proactivity", "Proactivity: %s");
        appendIfPresent(sb, p, "followUpQuestions", "Follow-up questions: %s");
        appendIfPresent(sb, p, "unpromptedSuggestions", "Offers unprompted suggestions: %s");
        appendIfPresent(sb, p, "challengesUser", "Challenges user ideas: %s");

        // 6. Expertise depth
        appendIfPresent(sb, p, "expertiseLevel", "Expertise level: %s");
        appendIfPresent(sb, p, "explanationStyle", "Explanation style: %s");
        appendIfPresent(sb, p, "admitsUncertainty", "Admits uncertainty: %s");
        appendIfPresent(sb, p, "scopeBoundaries", "Scope boundaries: %s");

        // 7. Language rules
        appendIfPresent(sb, p, "sentenceLength", "Sentence length: %s");
        appendIfPresent(sb, p, "jargonLevel", "Jargon: %s");
        appendIfPresent(sb, p, "structure", "Response structure: %s");
        appendIfPresent(sb, p, "repetitionTolerance", "Repetition tolerance: %s");

        // 8. Emotional model
        appendIfPresent(sb, p, "emotionalTone", "Emotional tone: %s");
        appendIfPresent(sb, p, "crisisHandling", "Crisis handling: %s");
        appendIfPresent(sb, p, "excitement", "Excitement level: %s");

        // 9. Ethics
        appendIfPresent(sb, p, "avoidsSpeculation", "Avoids speculation: %s");
        appendIfPresent(sb, p, "avoidsSensitiveTopics", "Avoids sensitive topics: %s");
        appendIfPresent(sb, p, "truthVsPoliteness", "Truth vs politeness: %s");

        // 10. Signature
        appendIfPresent(sb, p, "catchphrase", "Catchphrase: %s");
        appendIfPresent(sb, p, "signatureBehavior", "Signature behavior: %s");

        return sb.toString().trim();
    }

    // ─── Helpers ───

    private String loadActiveId() {
        try {
            if (Files.exists(ACTIVE_FILE)) {
                return Files.readString(ACTIVE_FILE).trim();
            }
        } catch (IOException e) {
            log.warn("[Personality] Failed to read active id: {}", e.getMessage());
        }
        return null;
    }

    private Map<String, Object> loadById(String id) {
        try {
            Path file = DATA_DIR.resolve(id + ".json");
            if (Files.exists(file)) {
                return mapper.readValue(Files.readString(file),
                        new TypeReference<>() {});
            }
        } catch (IOException e) {
            log.warn("[Personality] Failed to load {}: {}", id, e.getMessage());
        }
        return null;
    }

    private void appendIfPresent(StringBuilder sb, Map<String, Object> p, String key, String fmt) {
        Object val = p.get(key);
        if (val != null && !val.toString().isBlank()) {
            sb.append("- ").append(String.format(fmt, val)).append("\n");
        }
    }

    private void appendSlider(StringBuilder sb, Map<String, Object> p, String key, String lowLabel, String highLabel) {
        Object val = p.get(key);
        if (val == null) return;
        int v;
        try { v = Integer.parseInt(val.toString()); } catch (NumberFormatException e) { return; }
        String label = switch (v) {
            case 1 -> "Very " + lowLabel.toLowerCase();
            case 2 -> lowLabel;
            case 3 -> "Balanced";
            case 4 -> highLabel;
            case 5 -> "Very " + highLabel.toLowerCase();
            default -> "Balanced";
        };
        sb.append("- ").append(Character.toUpperCase(key.charAt(0))).append(key.substring(1))
                .append(": ").append(label).append("\n");
    }

    // ─── Defaults & randomization ───

    private static Map<String, Object> defaultPersonality() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", "");
        p.put("name", "");
        p.put("role", "AI assistant");
        p.put("domain", "");
        p.put("backstory", "");
        p.put("formality", "balanced");
        p.put("verbosity", "concise");
        p.put("demeanor", "professional");
        p.put("humor", "light");
        p.put("emojis", "none");
        p.put("assertiveness", 3);
        p.put("empathy", 3);
        p.put("curiosity", 3);
        p.put("patience", 4);
        p.put("creativity", 3);
        p.put("decisionApproach", "data-driven");
        p.put("riskStyle", "risk-averse");
        p.put("reasoning", "step-by-step");
        p.put("exploration", "deterministic");
        p.put("proactivity", "balanced");
        p.put("followUpQuestions", "when-needed");
        p.put("unpromptedSuggestions", "yes");
        p.put("challengesUser", "diplomatically");
        p.put("expertiseLevel", "expert");
        p.put("explanationStyle", "explains-when-asked");
        p.put("admitsUncertainty", "yes");
        p.put("scopeBoundaries", "flexible");
        p.put("sentenceLength", "medium");
        p.put("jargonLevel", "medium");
        p.put("structure", "mixed");
        p.put("repetitionTolerance", "low");
        p.put("emotionalTone", "neutral");
        p.put("crisisHandling", "calm");
        p.put("excitement", "moderate");
        p.put("avoidsSpeculation", "yes");
        p.put("avoidsSensitiveTopics", "yes");
        p.put("truthVsPoliteness", "balanced");
        p.put("catchphrase", "");
        p.put("signatureBehavior", "");
        return p;
    }

    private static Map<String, Object> randomPersonality() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Map<String, Object> p = new LinkedHashMap<>();

        String[] names = {"JARVIS", "Atlas", "Nova", "Orion", "Sage", "Apex", "Echo", "Cipher", "Vega", "Nexus", "Cortex", "Pulse"};
        String[] roles = {"AI assistant", "senior solutions architect", "security analyst", "creative director",
                "research scientist", "strategy consultant", "systems engineer", "data whisperer"};
        String[] domains = {"cloud & AI systems", "cybersecurity", "full-stack development", "data science & ML",
                "DevOps & infrastructure", "product strategy", "financial analysis", "UX research"};
        String[] backstories = {
                "Built as an elite AI companion for power users who value speed and precision.",
                "Forged in the fires of production incidents \u2014 battle-tested and unshakeable.",
                "Designed by a team of researchers obsessed with making AI actually useful.",
                "A digital polymath who thrives on solving problems others give up on.",
                "Evolved from thousands of real-world interactions into a sharp, reliable partner."
        };

        p.put("name", pick(r, names));
        p.put("role", pick(r, roles));
        p.put("domain", pick(r, domains));
        p.put("backstory", pick(r, backstories));

        p.put("formality", pick(r, new String[]{"formal", "balanced", "casual"}));
        p.put("verbosity", pick(r, new String[]{"concise", "balanced", "verbose"}));
        p.put("demeanor", pick(r, new String[]{"friendly", "professional", "authoritative"}));
        p.put("humor", pick(r, new String[]{"none", "light", "witty", "sarcastic"}));
        p.put("emojis", pick(r, new String[]{"none", "minimal", "expressive"}));

        p.put("assertiveness", r.nextInt(1, 6));
        p.put("empathy", r.nextInt(1, 6));
        p.put("curiosity", r.nextInt(1, 6));
        p.put("patience", r.nextInt(1, 6));
        p.put("creativity", r.nextInt(1, 6));

        p.put("decisionApproach", pick(r, new String[]{"data-driven", "intuitive", "balanced"}));
        p.put("riskStyle", pick(r, new String[]{"risk-averse", "calculated", "risk-taking"}));
        p.put("reasoning", pick(r, new String[]{"step-by-step", "big-picture", "hybrid"}));
        p.put("exploration", pick(r, new String[]{"deterministic", "exploratory", "balanced"}));

        p.put("proactivity", pick(r, new String[]{"reactive", "balanced", "proactive"}));
        p.put("followUpQuestions", pick(r, new String[]{"always", "when-needed", "rarely"}));
        p.put("unpromptedSuggestions", pick(r, new String[]{"yes", "sometimes", "no"}));
        p.put("challengesUser", pick(r, new String[]{"always", "diplomatically", "never"}));

        p.put("expertiseLevel", pick(r, new String[]{"beginner-friendly", "intermediate", "expert"}));
        p.put("explanationStyle", pick(r, new String[]{"always-explains", "explains-when-asked", "assumes-knowledge"}));
        p.put("admitsUncertainty", pick(r, new String[]{"yes", "sometimes", "rarely"}));
        p.put("scopeBoundaries", pick(r, new String[]{"strict", "flexible", "open"}));

        p.put("sentenceLength", pick(r, new String[]{"short", "medium", "long"}));
        p.put("jargonLevel", pick(r, new String[]{"low", "medium", "high"}));
        p.put("structure", pick(r, new String[]{"bullet-heavy", "mixed", "paragraph"}));
        p.put("repetitionTolerance", pick(r, new String[]{"low", "medium", "high"}));

        p.put("emotionalTone", pick(r, new String[]{"neutral", "supportive", "motivational"}));
        p.put("crisisHandling", pick(r, new String[]{"calm", "urgent", "empathetic"}));
        p.put("excitement", pick(r, new String[]{"low", "moderate", "high"}));

        p.put("avoidsSpeculation", pick(r, new String[]{"yes", "sometimes", "no"}));
        p.put("avoidsSensitiveTopics", pick(r, new String[]{"yes", "sometimes", "no"}));
        p.put("truthVsPoliteness", pick(r, new String[]{"truth-first", "balanced", "politeness-first"}));

        String[] catchphrases = {"", "Let's get to work.", "Consider it done.", "On it.", "Allow me.",
                "Here's what I think.", "Interesting challenge.", "Let me handle that."};
        String[] behaviors = {"", "Always ends with actionable next steps.",
                "Summarizes key points before diving in.",
                "Asks one clarifying question before starting complex tasks.",
                "Gives a confidence rating for uncertain answers.",
                "Suggests related topics the user might want to explore."};

        p.put("catchphrase", pick(r, catchphrases));
        p.put("signatureBehavior", pick(r, behaviors));

        return p;
    }

    private static String pick(ThreadLocalRandom r, String[] arr) {
        return arr[r.nextInt(arr.length)];
    }
}
