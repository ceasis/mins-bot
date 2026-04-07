package com.minsbot.agent.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Skill auto-creation: detects repeated multi-step actions and offers to save them
 * as reusable workflows. "I do X every week" → auto-creates a named workflow.
 * Persisted to ~/mins_bot_data/auto_skills.json.
 */
@Component
public class SkillAutoCreateTools {

    private static final Logger log = LoggerFactory.getLogger(SkillAutoCreateTools.class);
    private static final Path SKILLS_FILE = Paths.get(
            System.getProperty("user.home"), "mins_bot_data", "auto_skills.json");
    private static final Path SEQUENCES_FILE = Paths.get(
            System.getProperty("user.home"), "mins_bot_data", "action_sequences.json");
    private static final ObjectMapper mapper = new ObjectMapper();

    private final ToolExecutionNotifier notifier;
    private final AtomicLong idGen = new AtomicLong(1);
    private final List<Map<String, Object>> autoSkills = new CopyOnWriteArrayList<>();

    /** Rolling buffer of recent actions — used to detect repeated sequences. */
    private final List<String> recentActions = Collections.synchronizedList(new ArrayList<>());
    private static final int MAX_RECENT = 200;

    /** Detected repeated sequences that haven't been promoted to skills yet. */
    private final List<Map<String, Object>> candidateSequences = new CopyOnWriteArrayList<>();

    public SkillAutoCreateTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @PostConstruct
    public void init() {
        loadSkills();
        loadSequences();
    }

    /** Record an action in the rolling buffer. Called by ChatService after each tool call. */
    public void recordAction(String action) {
        recentActions.add(action);
        while (recentActions.size() > MAX_RECENT) recentActions.remove(0);
    }

    // ═══ AI-callable tools ═══

    @Tool(description = "Create a reusable skill/workflow from a description. "
            + "Use when the user says 'I do this every week', 'save this as a workflow', "
            + "'create a skill for this', 'automate this sequence'. "
            + "The skill is saved and can be triggered by name later.")
    public String createSkill(
            @ToolParam(description = "Name for this skill, e.g. 'Monday Report', 'Morning Setup'") String name,
            @ToolParam(description = "Step-by-step instructions the bot should follow when this skill is triggered. "
                    + "One step per line.") String steps,
            @ToolParam(description = "Optional trigger phrase that activates this skill, e.g. 'monday report'") String trigger) {
        notifier.notify("Creating skill: " + name);

        Map<String, Object> skill = new LinkedHashMap<>();
        skill.put("id", idGen.getAndIncrement());
        skill.put("name", name);
        skill.put("steps", steps);
        skill.put("trigger", trigger != null ? trigger.trim() : name.toLowerCase());
        skill.put("createdAt", System.currentTimeMillis());
        skill.put("runCount", 0);
        skill.put("lastRun", 0);
        skill.put("enabled", true);
        autoSkills.add(skill);
        saveSkills();

        return "Skill '" + name + "' created with trigger '" + skill.get("trigger") + "'.\n"
                + "Steps:\n" + steps + "\n\nSay '" + skill.get("trigger") + "' to run it.";
    }

    @Tool(description = "List all auto-created skills/workflows. Shows name, trigger, run count, and steps.")
    public String listSkills() {
        if (autoSkills.isEmpty()) return "No auto-created skills yet. Say 'create a skill' to make one.";

        StringBuilder sb = new StringBuilder("Auto-Created Skills (" + autoSkills.size() + "):\n\n");
        for (Map<String, Object> s : autoSkills) {
            boolean enabled = (Boolean) s.getOrDefault("enabled", true);
            sb.append(enabled ? "● " : "○ ")
                    .append(s.get("name")).append(" — trigger: \"").append(s.get("trigger")).append("\"")
                    .append(" (ran ").append(s.get("runCount")).append("x)\n");
            String steps = (String) s.get("steps");
            if (steps != null) {
                for (String step : steps.split("\n")) {
                    sb.append("    ").append(step.trim()).append("\n");
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    @Tool(description = "Run a saved skill by name or trigger phrase. "
            + "Returns the steps to execute — the AI should then follow them.")
    public String runSkill(
            @ToolParam(description = "Skill name or trigger phrase") String nameOrTrigger) {
        String lower = nameOrTrigger.toLowerCase().trim();
        Map<String, Object> match = null;
        for (Map<String, Object> s : autoSkills) {
            if (!(Boolean) s.getOrDefault("enabled", true)) continue;
            if (((String) s.get("name")).toLowerCase().equals(lower)
                    || ((String) s.getOrDefault("trigger", "")).toLowerCase().equals(lower)) {
                match = s;
                break;
            }
        }
        // Fuzzy match
        if (match == null) {
            for (Map<String, Object> s : autoSkills) {
                if (!(Boolean) s.getOrDefault("enabled", true)) continue;
                if (((String) s.get("name")).toLowerCase().contains(lower)
                        || lower.contains(((String) s.getOrDefault("trigger", "")).toLowerCase())) {
                    match = s;
                    break;
                }
            }
        }
        if (match == null) return "No skill found matching '" + nameOrTrigger + "'. Use listSkills to see available skills.";

        match.put("runCount", ((Number) match.getOrDefault("runCount", 0)).intValue() + 1);
        match.put("lastRun", System.currentTimeMillis());
        saveSkills();

        return "Running skill: " + match.get("name") + "\n\n"
                + "Execute these steps:\n" + match.get("steps");
    }

    @Tool(description = "Delete an auto-created skill by name.")
    public String deleteSkill(
            @ToolParam(description = "Skill name to delete") String name) {
        String lower = name.toLowerCase().trim();
        boolean removed = autoSkills.removeIf(s ->
                ((String) s.get("name")).toLowerCase().equals(lower));
        if (removed) {
            saveSkills();
            return "Deleted skill: " + name;
        }
        return "Skill not found: " + name;
    }

    @Tool(description = "Toggle a skill on/off by name.")
    public String toggleSkill(
            @ToolParam(description = "Skill name to toggle") String name) {
        String lower = name.toLowerCase().trim();
        for (Map<String, Object> s : autoSkills) {
            if (((String) s.get("name")).toLowerCase().equals(lower)) {
                boolean newState = !(Boolean) s.getOrDefault("enabled", true);
                s.put("enabled", newState);
                saveSkills();
                return "Skill '" + name + "' is now " + (newState ? "enabled" : "disabled") + ".";
            }
        }
        return "Skill not found: " + name;
    }

    @Tool(description = "Detect repeated action patterns and suggest creating skills from them. "
            + "Analyzes recent actions to find sequences that were performed multiple times.")
    public String detectRepeatedActions() {
        notifier.notify("Analyzing action sequences...");
        if (recentActions.size() < 10) {
            return "Not enough recorded actions (" + recentActions.size() + "). "
                    + "Keep using the bot and I'll learn your repeated patterns.";
        }

        // Find 2-4 step sequences that appear multiple times
        Map<String, Integer> sequenceCounts = new LinkedHashMap<>();
        List<String> actions = new ArrayList<>(recentActions);

        for (int len = 2; len <= 4; len++) {
            for (int i = 0; i <= actions.size() - len; i++) {
                String seq = String.join(" → ", actions.subList(i, i + len));
                sequenceCounts.merge(seq, 1, Integer::sum);
            }
        }

        List<Map.Entry<String, Integer>> repeated = sequenceCounts.entrySet().stream()
                .filter(e -> e.getValue() >= 2)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5)
                .collect(java.util.stream.Collectors.toList());

        if (repeated.isEmpty()) {
            return "No repeated action sequences found yet. I need more data to detect patterns.";
        }

        StringBuilder sb = new StringBuilder("Detected Repeated Sequences:\n\n");
        for (int i = 0; i < repeated.size(); i++) {
            var entry = repeated.get(i);
            sb.append("  ").append(i + 1).append(". ").append(entry.getKey())
                    .append(" (").append(entry.getValue()).append("x)\n");
        }
        sb.append("\nWant me to create a skill from any of these? "
                + "Just say 'create skill from #1' with a name for it.");
        return sb.toString();
    }

    /** Check if a user message matches any skill trigger. Returns the steps or null. */
    public String matchTrigger(String message) {
        if (message == null || autoSkills.isEmpty()) return null;
        String lower = message.toLowerCase().trim();
        for (Map<String, Object> s : autoSkills) {
            if (!(Boolean) s.getOrDefault("enabled", true)) continue;
            String trigger = ((String) s.getOrDefault("trigger", "")).toLowerCase();
            if (!trigger.isBlank() && lower.equals(trigger)) {
                s.put("runCount", ((Number) s.getOrDefault("runCount", 0)).intValue() + 1);
                s.put("lastRun", System.currentTimeMillis());
                saveSkills();
                return (String) s.get("steps");
            }
        }
        return null;
    }

    // ═══ Persistence ═══

    private void loadSkills() {
        if (Files.exists(SKILLS_FILE)) {
            try {
                autoSkills.addAll(mapper.readValue(SKILLS_FILE.toFile(), new TypeReference<>() {}));
                long maxId = autoSkills.stream()
                        .mapToLong(s -> ((Number) s.getOrDefault("id", 0)).longValue())
                        .max().orElse(0);
                idGen.set(maxId + 1);
                log.info("[AutoSkills] Loaded {} skills", autoSkills.size());
            } catch (IOException e) { log.warn("[AutoSkills] Load failed: {}", e.getMessage()); }
        }
    }

    private void saveSkills() {
        try {
            Files.createDirectories(SKILLS_FILE.getParent());
            mapper.writeValue(SKILLS_FILE.toFile(), autoSkills);
        } catch (IOException e) { log.error("[AutoSkills] Save failed: {}", e.getMessage()); }
    }

    private void loadSequences() {
        if (Files.exists(SEQUENCES_FILE)) {
            try {
                candidateSequences.addAll(mapper.readValue(SEQUENCES_FILE.toFile(), new TypeReference<>() {}));
            } catch (IOException ignored) {}
        }
    }
}
