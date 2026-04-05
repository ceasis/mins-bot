package com.minsbot.agent.tools;

import com.minsbot.FloatingAppLauncher;
import com.minsbot.agent.SystemContextProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists Mins Bot's own settings in ~/mins_bot_data/minsbot_config.txt (not the user's personal_config.txt).
 */
@Component
public class MinsbotConfigTools {

    private static final Path CONFIG_PATH =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "minsbot_config.txt");

    private final ToolExecutionNotifier notifier;

    public MinsbotConfigTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @Tool(description = "Save the assistant's display name to ~/mins_bot_data/minsbot_config.txt under ## Bot name. "
            + "Call when the user names you or renames you: e.g. 'your name is Jarvis', 'call yourself Alex', "
            + "'I will call you Buddy', 'change your name to Nova'. This persists across restarts and updates the window title. "
            + "Do NOT use this for the human user's name — use updatePersonalInfo for that. "
            + "Pass an empty string to clear the custom name (falls back to Mins Bot).")
    public String setBotDisplayName(
            @ToolParam(description = "Display name for the assistant, or empty to clear") String name) {
        notifier.notify("Saving bot display name...");
        try {
            SystemContextProvider.ensureMinsbotConfigFileExists();
            String raw = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            String cleaned = sanitizeDisplayName(name);
            String updated = replaceBotNameLine(raw, cleaned);
            Files.writeString(CONFIG_PATH, updated, StandardCharsets.UTF_8);
            FloatingAppLauncher.refreshBotName();
            if (cleaned.isEmpty()) {
                return "Bot display name cleared. Using default \"Mins Bot\" until you set a name again.";
            }
            return "Saved your name as \"" + cleaned + "\" in minsbot_config.txt. I'll remember it.";
        } catch (IOException e) {
            return "Could not save bot name: " + e.getMessage();
        }
    }

    @Tool(description = "Read the assistant's saved display name from minsbot_config.txt (## Bot name). "
            + "Use when the user asks what you are called or what name is saved for you.")
    public String getBotDisplayName() {
        notifier.notify("Reading bot display name...");
        String n = SystemContextProvider.loadBotName();
        if (n == null || n.isBlank() || "-".equals(n.trim())) {
            return "No custom name is set in minsbot_config.txt — I go by Mins Bot.";
        }
        return "My saved display name is: " + n.trim();
    }

    private static String sanitizeDisplayName(String name) {
        if (name == null) return "";
        String t = name.trim().replace("\r", " ").replace("\n", " ");
        t = t.replaceAll("[^\\p{L}\\p{N}\\s'.\\-]", "");
        if (t.length() > 80) {
            t = t.substring(0, 80).trim();
        }
        return t;
    }

    /**
     * Set the {@code - name:} line under {@code ## Bot name}, or insert the section if missing.
     */
    static String replaceBotNameLine(String raw, String newName) {
        String text = raw.replace("\r\n", "\n").replace("\r", "\n");
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).trim().equalsIgnoreCase("## Bot name")) {
                continue;
            }
            int j = i + 1;
            while (j < lines.size()) {
                String row = lines.get(j);
                if (row.trim().startsWith("##")) {
                    lines.add(j, "- name: " + newName);
                    return joinLines(lines);
                }
                if (row.trim().startsWith("- name:")) {
                    lines.set(j, "- name: " + newName);
                    return joinLines(lines);
                }
                j++;
            }
            lines.add("- name: " + newName);
            return joinLines(lines);
        }
        String trimmed = text.trim();
        StringBuilder sb = new StringBuilder(trimmed);
        if (!trimmed.isEmpty() && !trimmed.endsWith("\n")) {
            sb.append('\n');
        }
        if (!trimmed.isEmpty()) {
            sb.append('\n');
        }
        sb.append("## Bot name\n- name: ").append(newName).append('\n');
        return sb.toString();
    }

    private static String joinLines(List<String> lines) {
        String s = String.join("\n", lines);
        return s.endsWith("\n") ? s : s + "\n";
    }
}
