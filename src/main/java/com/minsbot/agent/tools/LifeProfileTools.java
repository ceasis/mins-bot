package com.minsbot.agent.tools;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tools for reading and updating the user's life profile (~/mins_bot_data/life_profile.txt).
 * This is a rich personal profile covering routines, preferences, relationships, goals, health,
 * finance, locations, vehicles, pets, important dates, and freeform notes. The file is loaded
 * into the AI system prompt as LIFE PROFILE context, so the bot can give deeply personalized
 * responses. Use these tools when the user shares life details that go beyond basic personal info.
 */
@Component
public class LifeProfileTools {

    private static final Path LIFE_PROFILE_PATH =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "life_profile.txt");

    private static final String DEFAULT_TEMPLATE = """
            # Life Profile
            Rich personal profile for deeply personalized responses. Fill in and keep updated.

            ## Routines
            -

            ## Preferences
            -

            ## Relationships
            -

            ## Goals
            -

            ## Health
            -

            ## Finance
            -

            ## Locations
            -

            ## Vehicles
            -

            ## Pets
            -

            ## ImportantDates
            -

            ## Notes
            -
            """;

    private static final Pattern SECTION_PATTERN = Pattern.compile("(^##\\s+.+$)", Pattern.MULTILINE);

    private final ToolExecutionNotifier notifier;

    public LifeProfileTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @Tool(description = "Read the entire life profile (routines, preferences, relationships, goals, health, finance, locations, vehicles, pets, important dates, notes). "
            + "Use when the user asks what you know about their life or to see their full profile.")
    public String getLifeProfile() {
        notifier.notify("Reading life profile...");
        try {
            ensureFileExists();
            if (!Files.exists(LIFE_PROFILE_PATH)) {
                return "Life profile file does not exist yet.";
            }
            String content = Files.readString(LIFE_PROFILE_PATH, StandardCharsets.UTF_8).trim();
            return content.isEmpty() ? "Life profile is empty." : content;
        } catch (IOException e) {
            return "Failed to read life profile: " + e.getMessage();
        }
    }

    @Tool(description = "Read a specific section from the life profile. Sections: Routines, Preferences, Relationships, Goals, Health, Finance, Locations, Vehicles, Pets, ImportantDates, Notes. "
            + "Use when the user asks about a specific area of their life profile.")
    public String getLifeProfileSection(
            @ToolParam(description = "Section name, e.g. 'Routines', 'Preferences', 'Relationships', 'Goals', 'Health', 'Finance', 'Locations', 'Vehicles', 'Pets', 'ImportantDates', 'Notes'") String section) {
        notifier.notify("Reading life profile section: " + section + "...");
        try {
            ensureFileExists();
            String full = Files.readString(LIFE_PROFILE_PATH, StandardCharsets.UTF_8);
            String body = extractSectionBody(full, section.trim());
            if (body == null) {
                return "Section '" + section.trim() + "' not found in life profile.";
            }
            return "## " + section.trim() + "\n" + body;
        } catch (IOException e) {
            return "Failed to read life profile: " + e.getMessage();
        }
    }

    @Tool(description = "Replace the entire content of a section in the life profile. Use when the user wants to rewrite a section completely "
            + "(e.g. update their full morning routine, replace all goals, rewrite their health info). "
            + "Section should match an existing heading or a new one. Content is the full text to set.")
    public String updateLifeProfileSection(
            @ToolParam(description = "Section name, e.g. 'Routines', 'Goals', 'Health'") String section,
            @ToolParam(description = "The full content to set for this section") String content) {
        notifier.notify("Updating life profile section: " + section + "...");
        try {
            ensureFileExists();
            String full = Files.readString(LIFE_PROFILE_PATH, StandardCharsets.UTF_8);
            String updated = replaceOrAddSection(full, section.trim(), content != null ? content.trim() : "");
            Files.writeString(LIFE_PROFILE_PATH, updated, StandardCharsets.UTF_8);
            return "Life profile updated: section '" + section.trim() + "' is now set. It will be used in future conversations.";
        } catch (IOException e) {
            return "Failed to update life profile: " + e.getMessage();
        }
    }

    @Tool(description = "Add a bullet-point entry to a section in the life profile without replacing existing content. "
            + "Use when the user shares a new detail to add (e.g. a new relationship, a new goal, a new allergy, a new favorite restaurant). "
            + "The entry will be added as a '- ' bullet point.")
    public String appendToLifeProfileSection(
            @ToolParam(description = "Section name, e.g. 'Relationships', 'Goals', 'Locations'") String section,
            @ToolParam(description = "The entry to add (e.g. 'Brother: Marco, birthday June 15', 'Learn guitar by December')") String entry) {
        notifier.notify("Adding to life profile section: " + section + "...");
        try {
            ensureFileExists();
            String full = Files.readString(LIFE_PROFILE_PATH, StandardCharsets.UTF_8);
            String updated = appendToSection(full, section.trim(), entry.trim());
            Files.writeString(LIFE_PROFILE_PATH, updated, StandardCharsets.UTF_8);
            return "Life profile: added entry to '" + section.trim() + "'.";
        } catch (IOException e) {
            return "Failed to update life profile: " + e.getMessage();
        }
    }

    @Tool(description = "Remove lines matching a substring from a section in the life profile. "
            + "Use when the user wants to remove specific info (e.g. remove a relationship entry, delete a goal, remove a location).")
    public String removeFromLifeProfileSection(
            @ToolParam(description = "Section name, e.g. 'Relationships', 'Goals', 'Locations'") String section,
            @ToolParam(description = "Substring to match — all lines containing this text will be removed") String substring) {
        notifier.notify("Removing from life profile section: " + section + "...");
        try {
            ensureFileExists();
            String full = Files.readString(LIFE_PROFILE_PATH, StandardCharsets.UTF_8);
            String updated = removeFromSection(full, section.trim(), substring.trim());
            Files.writeString(LIFE_PROFILE_PATH, updated, StandardCharsets.UTF_8);
            return "Life profile: removed matching entries from '" + section.trim() + "'.";
        } catch (IOException e) {
            return "Failed to update life profile: " + e.getMessage();
        }
    }

    @Tool(description = "Search across all sections of the life profile for a keyword or phrase. "
            + "Use when the user asks 'do you know about X' or you need to find where something is stored in the profile. "
            + "Returns all matching lines with their section context.")
    public String searchLifeProfile(
            @ToolParam(description = "The keyword or phrase to search for (case-insensitive)") String query) {
        notifier.notify("Searching life profile for: " + query + "...");
        try {
            ensureFileExists();
            if (!Files.exists(LIFE_PROFILE_PATH)) {
                return "Life profile file does not exist yet.";
            }
            String full = Files.readString(LIFE_PROFILE_PATH, StandardCharsets.UTF_8);
            String text = full.replace("\r\n", "\n").replace("\r", "\n");
            String queryLower = query.trim().toLowerCase();

            List<String> results = new ArrayList<>();
            String currentSection = "(header)";

            for (String line : text.split("\n")) {
                if (line.startsWith("## ")) {
                    currentSection = line.substring(3).trim();
                }
                if (line.toLowerCase().contains(queryLower)) {
                    results.add("[" + currentSection + "] " + line.trim());
                }
            }

            if (results.isEmpty()) {
                return "No matches found for '" + query.trim() + "' in life profile.";
            }
            StringBuilder sb = new StringBuilder("Found " + results.size() + " match(es) for '" + query.trim() + "':\n");
            for (String r : results) {
                sb.append(r).append("\n");
            }
            return sb.toString().trim();
        } catch (IOException e) {
            return "Failed to search life profile: " + e.getMessage();
        }
    }

    // ─── internal helpers ───────────────────────────────────────────────

    private void ensureFileExists() throws IOException {
        if (!Files.exists(LIFE_PROFILE_PATH)) {
            Files.createDirectories(LIFE_PROFILE_PATH.getParent());
            Files.writeString(LIFE_PROFILE_PATH, DEFAULT_TEMPLATE, StandardCharsets.UTF_8);
        }
    }

    /**
     * Extract the body text of a section (between its heading and the next heading).
     * Returns null if the section is not found.
     */
    private String extractSectionBody(String full, String sectionTitle) {
        String heading = "## " + sectionTitle;
        String text = full.replace("\r\n", "\n").replace("\r", "\n");
        if (!text.endsWith("\n")) text += "\n";

        int headingStart = findSectionStart(text, heading);
        if (headingStart < 0) return null;

        int bodyStart = text.indexOf('\n', headingStart) + 1;
        int nextSection = findNextSectionStart(text, bodyStart);
        int end = nextSection < 0 ? text.length() : nextSection;
        return text.substring(bodyStart, end).trim();
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

    /**
     * Append a bullet entry to an existing section. If the section body is just "-", replace it.
     */
    private String appendToSection(String full, String sectionTitle, String newEntry) {
        String heading = "## " + sectionTitle;
        String text = full.replace("\r\n", "\n").replace("\r", "\n");
        if (!text.endsWith("\n")) text += "\n";

        int headingStart = findSectionStart(text, heading);
        if (headingStart >= 0) {
            int bodyStart = text.indexOf('\n', headingStart) + 1;
            int nextSection = findNextSectionStart(text, bodyStart);
            int end = nextSection < 0 ? text.length() : nextSection;
            String body = text.substring(bodyStart, end).trim();

            String bullet = "- " + newEntry;
            String newBody;
            if (body.equals("-") || body.isEmpty()) {
                newBody = bullet + "\n";
            } else {
                newBody = body + "\n" + bullet + "\n";
            }

            String before = text.substring(0, bodyStart);
            String after = end <= text.length() ? text.substring(end) : "";
            return before + newBody + after;
        }
        String toAppend = "\n\n" + heading + "\n- " + newEntry + "\n";
        return text.trim() + toAppend;
    }

    /**
     * Remove lines from a section that contain the given substring.
     * If the section becomes empty, resets it to "-".
     */
    private String removeFromSection(String full, String sectionTitle, String entrySubstring) {
        String heading = "## " + sectionTitle;
        String text = full.replace("\r\n", "\n").replace("\r", "\n");
        if (!text.endsWith("\n")) text += "\n";

        int headingStart = findSectionStart(text, heading);
        if (headingStart < 0) return text;

        int bodyStart = text.indexOf('\n', headingStart) + 1;
        int nextSection = findNextSectionStart(text, bodyStart);
        int end = nextSection < 0 ? text.length() : nextSection;
        String body = text.substring(bodyStart, end);

        StringBuilder filtered = new StringBuilder();
        for (String line : body.split("\n")) {
            if (!line.contains(entrySubstring)) {
                filtered.append(line).append("\n");
            }
        }
        String remaining = filtered.toString().trim();
        if (remaining.isEmpty()) remaining = "-";
        remaining += "\n";

        String before = text.substring(0, bodyStart);
        String after = end <= text.length() ? text.substring(end) : "";
        return before + remaining + after;
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
