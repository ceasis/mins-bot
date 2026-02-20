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
 * Tools for reading and updating the user's cron/scheduled-checks config (~/mins_bot_data/cron_config.md).
 * That file is loaded into the AI system prompt as SCHEDULED CHECKS, so the bot knows which
 * daily, weekly, or other recurring checks the user wants. Use these tools when the user
 * adds or changes scheduled checks or reminders.
 */
@Component
public class CronConfigTools {

    private static final Path CRON_CONFIG_PATH =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "cron_config.md");

    private static final String DEFAULT_TEMPLATE = """
            # Cron / scheduled checks
            Recurring checks and reminders the user wants to track.

            ## Daily checks
            -

            ## Weekly checks
            -

            ## Reminders
            -

            ## Other schedule
            -
            """;

    private static final Pattern SECTION_PATTERN = Pattern.compile("(^##\\s+.+$)", Pattern.MULTILINE);

    private final ToolExecutionNotifier notifier;

    public CronConfigTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @Tool(description = "Read the current cron/scheduled checks config (daily, weekly, reminders, etc.). Use when the user asks what checks are scheduled or to see their cron config.")
    public String getCronConfig() {
        notifier.notify("Reading cron config...");
        try {
            ensureFileExists();
            if (!Files.exists(CRON_CONFIG_PATH)) {
                return "Cron config file does not exist yet.";
            }
            String content = Files.readString(CRON_CONFIG_PATH, StandardCharsets.UTF_8).trim();
            return content.isEmpty() ? "Cron config is empty." : content;
        } catch (IOException e) {
            return "Failed to read cron config: " + e.getMessage();
        }
    }

    @Tool(description = "Update or add a section in the user's cron/scheduled checks config. Call this when the user adds or changes scheduled checks (e.g. daily backup check, weekly report, reminder). "
            + "Section should match an existing heading (e.g. 'Daily checks', 'Weekly checks', 'Reminders', 'Other schedule') or a new one. "
            + "Content is the text to set for that section (e.g. 'Backup status at 9:00', 'Review inbox every Monday').")
    public String updateCronInfo(
            @ToolParam(description = "Section heading, e.g. 'Daily checks', 'Weekly checks', 'Reminders', 'Other schedule'") String section,
            @ToolParam(description = "The scheduled check or reminder to store (e.g. 'Backup at 9:00', 'Weekly sync every Friday')") String content) {
        notifier.notify("Updating cron config...");
        try {
            ensureFileExists();
            String full = Files.readString(CRON_CONFIG_PATH, StandardCharsets.UTF_8);
            String updated = replaceOrAddSection(full, section.trim(), content != null ? content.trim() : "");
            Files.writeString(CRON_CONFIG_PATH, updated, StandardCharsets.UTF_8);
            return "Cron config updated: section '" + section.trim() + "' is now set. It will be used in future conversations.";
        } catch (IOException e) {
            return "Failed to update cron config: " + e.getMessage();
        }
    }

    private void ensureFileExists() throws IOException {
        if (!Files.exists(CRON_CONFIG_PATH)) {
            Files.createDirectories(CRON_CONFIG_PATH.getParent());
            Files.writeString(CRON_CONFIG_PATH, DEFAULT_TEMPLATE, StandardCharsets.UTF_8);
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
