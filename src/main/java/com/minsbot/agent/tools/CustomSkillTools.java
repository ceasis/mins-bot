package com.minsbot.agent.tools;

import com.minsbot.agent.SystemPromptService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Tools for reading, listing, and saving custom skills stored as Markdown files
 * in ~/mins_bot_data/skills/. Skills are user-written recipes the AI follows
 * when their name matches a user request.
 */
@Component
public class CustomSkillTools {

    private final SystemPromptService systemPromptService;
    private final ToolExecutionNotifier notifier;

    public CustomSkillTools(SystemPromptService systemPromptService,
                            ToolExecutionNotifier notifier) {
        this.systemPromptService = systemPromptService;
        this.notifier = notifier;
    }

    @Tool(description = "List all custom skills saved in the user's skills folder. "
            + "Returns skill names (one per line). Use when the user asks 'what skills do you have', "
            + "'list my skills', 'what can I teach you', or when deciding whether an existing skill "
            + "fits the current request.")
    public String listSkills() {
        notifier.notify("Listing custom skills...");
        List<String> skills = systemPromptService.listCustomSkills();
        if (skills.isEmpty()) {
            return "No custom skills saved yet. Drop Markdown files in "
                    + systemPromptService.getSkillsDir() + " to add new ones.";
        }
        return "Custom skills (" + skills.size() + "):\n  - "
                + String.join("\n  - ", skills);
    }

    @Tool(description = "Read the full content (steps and instructions) of a custom skill by name. "
            + "ALWAYS call this BEFORE executing a skill — never guess its content. "
            + "The skill name is the file name without the .md extension.")
    public String readSkill(
            @ToolParam(description = "Skill name (file name without .md), e.g. 'morning-brief'") String skillName) {
        notifier.notify("Reading skill: " + skillName);
        String content = systemPromptService.readCustomSkill(skillName);
        if (content == null) {
            return "Skill '" + skillName + "' not found. Available: "
                    + String.join(", ", systemPromptService.listCustomSkills());
        }
        return "=== Skill: " + skillName + " ===\n\n" + content;
    }

    @Tool(description = "Save a new custom skill or overwrite an existing one. "
            + "Use when the user says 'save this as a skill called X', 'remember this routine as X', "
            + "'create a skill for Y'. The content should be Markdown with steps the AI follows "
            + "when the skill is triggered.")
    public String saveSkill(
            @ToolParam(description = "Skill name — lowercase letters, digits, dashes, underscores only") String skillName,
            @ToolParam(description = "Markdown content with the skill's instructions and steps") String content) {
        notifier.notify("Saving skill: " + skillName);
        if (skillName == null || !skillName.matches("[a-zA-Z0-9_\\-]+")) {
            return "Invalid skill name. Use only letters, digits, dashes, and underscores.";
        }
        boolean ok = systemPromptService.saveCustomSkill(skillName, content);
        if (!ok) return "Failed to save skill '" + skillName + "'.";
        return "Saved skill: " + skillName + " (" + content.length() + " chars). "
                + "It will appear in your skills list on the next chat message.";
    }
}
