package com.minsbot.agent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tools for reading and updating the user's system config (~/mins_bot_data/system_config.md).
 * That file is loaded into the AI system prompt as SYSTEM CONFIG, so the bot knows machine
 * preferences, default apps, paths, network, etc. Use these tools when the user shares
 * system-related info to store for future conversations.
 */
@Component
public class SystemConfigTools {

    private static final Path SYSTEM_CONFIG_PATH =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "system_config.md");

    private static final String DEFAULT_TEMPLATE = """
            # System config
            Machine-specific and preference details. Use for paths, default apps, network, etc.

            ## Default browser
            -

            ## Preferred apps
            -

            ## Important paths
            -

            ## Network / VPN
            -
            """;

    private static final Pattern SECTION_PATTERN = Pattern.compile("(^##\\s+.+$)", Pattern.MULTILINE);

    private final ToolExecutionNotifier notifier;

    public SystemConfigTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @Tool(description = "Read the current system config (default browser, preferred apps, paths, network, etc.). Use when the user asks what you know about their system setup or to see system config.")
    public String getSystemConfig() {
        notifier.notify("Reading system config...");
        try {
            ensureFileExists();
            if (!Files.exists(SYSTEM_CONFIG_PATH)) {
                return "System config file does not exist yet.";
            }
            String content = Files.readString(SYSTEM_CONFIG_PATH, StandardCharsets.UTF_8).trim();
            return content.isEmpty() ? "System config is empty." : content;
        } catch (IOException e) {
            return "Failed to read system config: " + e.getMessage();
        }
    }

    @Tool(description = "Update or add a section in the user's system config. Call this when the user tells you system-related information (e.g. default browser, preferred apps, important folder paths, VPN or network details). "
            + "Section should match an existing heading (e.g. 'Default browser', 'Preferred apps', 'Important paths', 'Network / VPN') or a new one. "
            + "Content is the text to set for that section. Changes are used in future responses.")
    public String updateSystemInfo(
            @ToolParam(description = "Section heading, e.g. 'Default browser', 'Preferred apps', 'Important paths', 'Network / VPN'") String section,
            @ToolParam(description = "The information to store for this section (e.g. 'Chrome', 'Projects in D:\\work')") String content) {
        notifier.notify("Updating system config...");
        try {
            ensureFileExists();
            String full = Files.readString(SYSTEM_CONFIG_PATH, StandardCharsets.UTF_8);
            String updated = replaceOrAddSection(full, section.trim(), content != null ? content.trim() : "");
            Files.writeString(SYSTEM_CONFIG_PATH, updated, StandardCharsets.UTF_8);
            return "System config updated: section '" + section.trim() + "' is now set. It will be used in future conversations.";
        } catch (IOException e) {
            return "Failed to update system config: " + e.getMessage();
        }
    }

    private void ensureFileExists() throws IOException {
        if (!Files.exists(SYSTEM_CONFIG_PATH)) {
            Files.createDirectories(SYSTEM_CONFIG_PATH.getParent());
            Files.writeString(SYSTEM_CONFIG_PATH, DEFAULT_TEMPLATE, StandardCharsets.UTF_8);
        }
    }

    /**
     * Replace the body of a section (## SectionTitle) with new content, or append the section if not present.
     */
    private String replaceOrAddSection(String full, String sectionTitle, String newContent) {
        String heading = "## " + sectionTitle;
        String text = full.replace("\r\n", "\n").replace("\r", "\n");
        if (!text.endsWith("\n")) text += "\n";

        int headingStart = findSectionStart(text, heading);
        if (headingStart >= 0) {
            int bodyStart = text.indexOf('\n', headingStart) + 1;
            int nextSection = findNextSectionStart(text, bodyStart);
            int end = nextSection < 0 ? text.length() : nextSection;
            String before = text.substring(0, bodyStart);
            String after = end <= text.length() ? text.substring(end) : "";
            String replacement = newContent.isEmpty() ? "-" : newContent;
            if (!replacement.endsWith("\n")) replacement += "\n";
            return before + replacement + after;
        }
        String toAppend = "\n\n" + heading + "\n" + (newContent.isEmpty() ? "-" : newContent) + "\n";
        return text.trim() + toAppend;
    }

    private int findSectionStart(String text, String heading) {
        int i = 0;
        while (true) {
            int start = text.indexOf(heading, i);
            if (start < 0) return -1;
            if (start == 0 || text.charAt(start - 1) == '\n') return start;
            i = start + 1;
        }
    }

    private int findNextSectionStart(String text, int from) {
        if (from >= text.length()) return -1;
        Matcher m = SECTION_PATTERN.matcher(text);
        m.region(from, text.length());
        return m.find() ? m.start() : -1;
    }
}
