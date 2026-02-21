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
 * Tools for reading and updating the user's personal config (~/mins_bot_data/personal_config.txt).
 * That file is loaded into the AI system prompt as PERSONAL CONTEXT, so the bot can personalize
 * responses. Use these tools when the user shares new personal info (name, family, work, etc.)
 * so it is stored for future conversations.
 */
@Component
public class PersonalConfigTools {

    private static final Path PERSONAL_CONFIG_PATH =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "personal_config.txt");

    private static final String DEFAULT_TEMPLATE = """
            # Personal config
            Use this for personalized responses. Fill in and keep updated.

            ## Name
            -

            ## Birthdate
            -

            ## Kids
            -

            ## Partner / spouse
            -

            ## Work
            -
            """;

    private static final Pattern SECTION_PATTERN = Pattern.compile("(^##\\s+.+$)", Pattern.MULTILINE);

    private final ToolExecutionNotifier notifier;

    public PersonalConfigTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @Tool(description = "Read the current personal config (name, birthdate, family, work, etc.). Use when the user asks what you know about them or to see their personal config.")
    public String getPersonalConfig() {
        notifier.notify("Reading personal config...");
        try {
            ensureFileExists();
            if (!Files.exists(PERSONAL_CONFIG_PATH)) {
                return "Personal config file does not exist yet.";
            }
            String content = Files.readString(PERSONAL_CONFIG_PATH, StandardCharsets.UTF_8).trim();
            return content.isEmpty() ? "Personal config is empty." : content;
        } catch (IOException e) {
            return "Failed to read personal config: " + e.getMessage();
        }
    }

    @Tool(description = "Update or add a section in the user's personal config. Call this when the user tells you new personal information (e.g. their name, birthdate, kids' names, partner's name, job). "
            + "Section should match an existing heading (e.g. 'Name', 'Birthdate', 'Kids', 'Partner / spouse', 'Work') or a new one. "
            + "Content is the text to set for that section. Changes are used in future responses.")
    public String updatePersonalInfo(
            @ToolParam(description = "Section heading, e.g. 'Name', 'Kids', 'Partner / spouse', 'Work'") String section,
            @ToolParam(description = "The information to store for this section (e.g. 'Maria', 'Two kids: Leo 5, Emma 8')") String content) {
        notifier.notify("Updating personal config...");
        try {
            ensureFileExists();
            String full = Files.readString(PERSONAL_CONFIG_PATH, StandardCharsets.UTF_8);
            String updated = replaceOrAddSection(full, section.trim(), content != null ? content.trim() : "");
            Files.writeString(PERSONAL_CONFIG_PATH, updated, StandardCharsets.UTF_8);
            return "Personal config updated: section '" + section.trim() + "' is now set. It will be used in future conversations.";
        } catch (IOException e) {
            return "Failed to update personal config: " + e.getMessage();
        }
    }

    private void ensureFileExists() throws IOException {
        if (!Files.exists(PERSONAL_CONFIG_PATH)) {
            Files.createDirectories(PERSONAL_CONFIG_PATH.getParent());
            Files.writeString(PERSONAL_CONFIG_PATH, DEFAULT_TEMPLATE, StandardCharsets.UTF_8);
        }
    }

    /**
     * Replace the body of a section (## SectionTitle) with new content, or append the section if not present.
     */
    private String replaceOrAddSection(String full, String sectionTitle, String newContent) {
        String heading = "## " + sectionTitle;
        // Normalize line endings and ensure ends with newline for parsing
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
        // Section not found: append it
        String toAppend = "\n\n" + heading + "\n" + (newContent.isEmpty() ? "-" : newContent) + "\n";
        return text.trim() + toAppend;
    }

    private int findSectionStart(String text, String heading) {
        int i = 0;
        while (true) {
            int start = text.indexOf(heading, i);
            if (start < 0) return -1;
            // Must be at start of line (start == 0 or preceded by \n)
            if (start == 0 || text.charAt(start - 1) == '\n') {
                return start;
            }
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
