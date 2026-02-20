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

/**
 * Tools for reading and updating the user's primary directives file.
 * The directives are loaded into the AI system prompt on every request,
 * so changes take effect immediately.
 */
@Component
public class DirectivesTools {

    private static final Path DIRECTIVES_FILE =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "primary_directives.dat");

    private final ToolExecutionNotifier notifier;

    public DirectivesTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @Tool(description = "Read the user's primary directives. These are persistent instructions that guide your behavior across all conversations. Use when the user asks to see their current directives.")
    public String getDirectives() {
        notifier.notify("Reading directives...");
        try {
            if (!Files.exists(DIRECTIVES_FILE)) {
                return "No directives set yet. The user can ask you to set directives.";
            }
            String content = Files.readString(DIRECTIVES_FILE, StandardCharsets.UTF_8).trim();
            return content.isEmpty() ? "Directives file is empty." : content;
        } catch (IOException e) {
            return "Failed to read directives: " + e.getMessage();
        }
    }

    @Tool(description = "Set or replace the user's primary directives. These are persistent instructions that guide your behavior (e.g. 'always respond in Spanish', 'call me Boss'). This overwrites the entire directives file.")
    public String setDirectives(
            @ToolParam(description = "The full directives text to save") String directives) {
        notifier.notify("Updating directives...");
        try {
            Files.createDirectories(DIRECTIVES_FILE.getParent());
            Files.writeString(DIRECTIVES_FILE, directives != null ? directives.trim() : "", StandardCharsets.UTF_8);
            return "Directives updated. They will be applied to all future conversations.";
        } catch (IOException e) {
            return "Failed to save directives: " + e.getMessage();
        }
    }

    @Tool(description = "Append a line or paragraph to the existing directives without replacing them.")
    public String appendDirective(
            @ToolParam(description = "The directive text to add") String directive) {
        notifier.notify("Adding directive...");
        try {
            Files.createDirectories(DIRECTIVES_FILE.getParent());
            String existing = "";
            if (Files.exists(DIRECTIVES_FILE)) {
                existing = Files.readString(DIRECTIVES_FILE, StandardCharsets.UTF_8).trim();
            }
            String updated = existing.isEmpty() ? directive.trim() : existing + "\n" + directive.trim();
            Files.writeString(DIRECTIVES_FILE, updated, StandardCharsets.UTF_8);
            return "Directive added. Current directives:\n" + updated;
        } catch (IOException e) {
            return "Failed to append directive: " + e.getMessage();
        }
    }

    @Tool(description = "List all primary directives with their numbered positions (1-based). Use this before reordering so you know which number to move.")
    public String listDirectivesNumbered() {
        notifier.notify("Listing directives...");
        try {
            if (!Files.exists(DIRECTIVES_FILE)) {
                return "No directives set yet.";
            }
            List<String> lines = getNonEmptyLines();
            if (lines.isEmpty()) return "Directives file is empty.";
            StringBuilder sb = new StringBuilder("Current directives:\n");
            for (int i = 0; i < lines.size(); i++) {
                sb.append(i + 1).append(". ").append(lines.get(i)).append("\n");
            }
            return sb.toString().trim();
        } catch (IOException e) {
            return "Failed to read directives: " + e.getMessage();
        }
    }

    @Tool(description = "Move a directive to a new position. For example, to make directive #3 the top priority, call moveDirective(from=3, to=1). Other directives shift to make room.")
    public String moveDirective(
            @ToolParam(description = "Current position of the directive to move (1-based)") double fromRaw,
            @ToolParam(description = "New position to place it at (1-based)") double toRaw) {
        int from = (int) Math.round(fromRaw);
        int to = (int) Math.round(toRaw);
        notifier.notify("Reordering directives...");
        try {
            if (!Files.exists(DIRECTIVES_FILE)) {
                return "No directives to reorder.";
            }
            List<String> lines = getNonEmptyLines();
            if (lines.isEmpty()) return "No directives to reorder.";
            if (from < 1 || from > lines.size()) {
                return "Invalid 'from' position: " + from + ". Must be 1-" + lines.size() + ".";
            }
            if (to < 1 || to > lines.size()) {
                return "Invalid 'to' position: " + to + ". Must be 1-" + lines.size() + ".";
            }
            if (from == to) return "Directive is already at position " + to + ".";

            String moved = lines.remove(from - 1);
            lines.add(to - 1, moved);

            Files.createDirectories(DIRECTIVES_FILE.getParent());
            Files.writeString(DIRECTIVES_FILE, String.join("\n", lines), StandardCharsets.UTF_8);

            StringBuilder sb = new StringBuilder("Moved to #" + to + ". Updated order:\n");
            for (int i = 0; i < lines.size(); i++) {
                sb.append(i + 1).append(". ").append(lines.get(i)).append("\n");
            }
            return sb.toString().trim();
        } catch (IOException e) {
            return "Failed to reorder directives: " + e.getMessage();
        }
    }

    @Tool(description = "Remove a single directive by its position number (1-based). Other directives shift up.")
    public String removeDirective(
            @ToolParam(description = "Position of the directive to remove (1-based)") double positionRaw) {
        int position = (int) Math.round(positionRaw);
        notifier.notify("Removing directive...");
        try {
            if (!Files.exists(DIRECTIVES_FILE)) {
                return "No directives to remove.";
            }
            List<String> lines = getNonEmptyLines();
            if (position < 1 || position > lines.size()) {
                return "Invalid position: " + position + ". Must be 1-" + lines.size() + ".";
            }
            String removed = lines.remove(position - 1);
            Files.writeString(DIRECTIVES_FILE, String.join("\n", lines), StandardCharsets.UTF_8);

            StringBuilder sb = new StringBuilder("Removed: \"" + removed + "\"\nRemaining:\n");
            for (int i = 0; i < lines.size(); i++) {
                sb.append(i + 1).append(". ").append(lines.get(i)).append("\n");
            }
            return lines.isEmpty() ? "Removed: \"" + removed + "\"\nNo directives remaining." : sb.toString().trim();
        } catch (IOException e) {
            return "Failed to remove directive: " + e.getMessage();
        }
    }

    /** Reads non-empty lines from the directives file. */
    private List<String> getNonEmptyLines() throws IOException {
        String content = Files.readString(DIRECTIVES_FILE, StandardCharsets.UTF_8);
        List<String> lines = new ArrayList<>();
        for (String line : content.split("\n")) {
            if (!line.trim().isEmpty()) lines.add(line.trim());
        }
        return lines;
    }

    @Tool(description = "Clear all primary directives, removing all custom behavior instructions.")
    public String clearDirectives() {
        notifier.notify("Clearing directives...");
        try {
            if (Files.exists(DIRECTIVES_FILE)) {
                Files.delete(DIRECTIVES_FILE);
            }
            return "All directives cleared.";
        } catch (IOException e) {
            return "Failed to clear directives: " + e.getMessage();
        }
    }

    /** Called by SystemContextProvider to include directives in the system prompt. */
    public static String loadDirectivesForPrompt() {
        try {
            if (Files.exists(DIRECTIVES_FILE)) {
                String content = Files.readString(DIRECTIVES_FILE, StandardCharsets.UTF_8).trim();
                if (!content.isEmpty()) return content;
            }
        } catch (IOException ignored) {
        }
        return null;
    }
}
