package com.minsbot.agent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Searchable clipboard history manager. Tracks everything copied to the clipboard
 * and lets the user search, recall, and restore past clips.
 */
@Component
public class ClipboardHistoryTools {

    private static final int MAX_HISTORY = 200;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final ToolExecutionNotifier notifier;
    private final ClipboardTools clipboardTools;
    private final List<ClipEntry> history = new CopyOnWriteArrayList<>();
    private String lastSeen = null;

    public ClipboardHistoryTools(ToolExecutionNotifier notifier, ClipboardTools clipboardTools) {
        this.notifier = notifier;
        this.clipboardTools = clipboardTools;
    }

    /** Polls clipboard every 2 seconds to capture changes. */
    @Scheduled(fixedDelay = 2000)
    public void poll() {
        try {
            String current = clipboardTools.getClipboardText();
            if (current != null && !current.isBlank()
                    && !current.startsWith("(") && !current.startsWith("Could not")
                    && !current.startsWith("Clipboard unavailable")
                    && !current.equals(lastSeen)) {
                lastSeen = current;
                history.add(new ClipEntry(current, Instant.now()));
                while (history.size() > MAX_HISTORY) history.remove(0);
            }
        } catch (Exception ignored) {}
    }

    @Tool(description = "Show clipboard history — the last N items that were copied. "
            + "Use when the user asks 'what did I copy earlier?', 'clipboard history', "
            + "'show my recent copies', 'what was on my clipboard before?'.")
    public String showHistory(
            @ToolParam(description = "Number of recent items to show (1-50, default 10)") double count) {
        int n = Math.max(1, Math.min(50, (int) count));
        notifier.notify("Showing last " + n + " clipboard entries...");

        // Poll once to make sure current clipboard is captured
        poll();

        if (history.isEmpty()) {
            return "Clipboard history is empty. Nothing has been copied yet this session.";
        }

        int start = Math.max(0, history.size() - n);
        StringBuilder sb = new StringBuilder("Clipboard History (last " + Math.min(n, history.size()) + " entries):\n\n");
        for (int i = start; i < history.size(); i++) {
            ClipEntry e = history.get(i);
            String preview = e.text.length() > 120 ? e.text.substring(0, 120) + "..." : e.text;
            preview = preview.replace("\n", " ↵ ");
            sb.append(String.format("  %d. [%s] %s\n", i + 1, FMT.format(e.time), preview));
        }
        return sb.toString();
    }

    @Tool(description = "Search clipboard history for text matching a keyword or phrase. "
            + "Use when the user says 'find that thing I copied about X', 'search clipboard for X'.")
    public String searchHistory(
            @ToolParam(description = "Search keyword or phrase (case-insensitive)") String query) {
        notifier.notify("Searching clipboard history for: " + query);
        poll();

        if (history.isEmpty()) return "Clipboard history is empty.";

        String lowerQuery = query.toLowerCase();
        List<String> matches = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            ClipEntry e = history.get(i);
            if (e.text.toLowerCase().contains(lowerQuery)) {
                String preview = e.text.length() > 150 ? e.text.substring(0, 150) + "..." : e.text;
                preview = preview.replace("\n", " ↵ ");
                matches.add(String.format("  %d. [%s] %s", i + 1, FMT.format(e.time), preview));
            }
        }

        if (matches.isEmpty()) return "No clipboard entries matching '" + query + "'.";
        return "Found " + matches.size() + " matching clipboard entries:\n\n" + String.join("\n", matches);
    }

    /**
     * Return the Nth-most-recent clipboard entry (1-based, counted from the newest).
     * {@code n=1} → latest, {@code n=2} → one before that, etc.
     * Returns {@code null} if history has fewer than {@code n} entries.
     * Used by the Ctrl+Shift+V cycle hotkey — not @Tool because it's a helper, not AI-facing.
     */
    public String getEntryFromEnd(int n) {
        if (n <= 0) return null;
        // Make sure the very latest copy is captured before we read
        poll();
        int size = history.size();
        if (size < n) return null;
        return history.get(size - n).text;
    }

    @Tool(description = "Get the full content of a specific clipboard history entry by its number. "
            + "Use after showHistory or searchHistory to retrieve the complete text of a specific entry.")
    public String getHistoryEntry(
            @ToolParam(description = "Entry number from clipboard history (1-based)") double entryNumber) {
        int idx = (int) entryNumber - 1;
        if (idx < 0 || idx >= history.size()) {
            return "Invalid entry number. History has " + history.size() + " entries.";
        }
        ClipEntry e = history.get(idx);
        return "Clipboard entry #" + (idx + 1) + " (copied at " + FMT.format(e.time) + "):\n\n" + e.text;
    }

    @Tool(description = "Restore a clipboard history entry back to the current clipboard. "
            + "Use when the user says 'put that back on my clipboard', 'restore clip #N'.")
    public String restoreEntry(
            @ToolParam(description = "Entry number to restore to clipboard (1-based)") double entryNumber) {
        int idx = (int) entryNumber - 1;
        if (idx < 0 || idx >= history.size()) {
            return "Invalid entry number. History has " + history.size() + " entries.";
        }
        ClipEntry e = history.get(idx);
        notifier.notify("Restoring clipboard entry #" + (idx + 1) + "...");
        clipboardTools.setClipboardText(e.text);
        String preview = e.text.length() > 80 ? e.text.substring(0, 80) + "..." : e.text;
        return "Restored to clipboard: " + preview;
    }

    @Tool(description = "Clear all clipboard history. Use when the user says 'clear clipboard history', "
            + "'wipe clipboard log', 'forget clipboard'.")
    public String clearHistory() {
        int count = history.size();
        history.clear();
        lastSeen = null;
        return "Cleared " + count + " clipboard history entries.";
    }

    @Tool(description = "Get clipboard history statistics: total entries, unique entries, "
            + "most recent copy time, most frequently copied text.")
    public String historyStats() {
        poll();
        if (history.isEmpty()) return "Clipboard history is empty.";

        Map<String, Integer> freq = new LinkedHashMap<>();
        for (ClipEntry e : history) {
            String key = e.text.length() > 100 ? e.text.substring(0, 100) : e.text;
            freq.merge(key, 1, Integer::sum);
        }
        String mostCopied = freq.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(e -> e.getKey() + " (" + e.getValue() + " times)")
                .orElse("—");

        return "Clipboard History Stats:\n"
                + "  Total entries: " + history.size() + "\n"
                + "  Unique entries: " + freq.size() + "\n"
                + "  Oldest: " + FMT.format(history.get(0).time) + "\n"
                + "  Newest: " + FMT.format(history.get(history.size() - 1).time) + "\n"
                + "  Most copied: " + mostCopied;
    }

    // ─────────────────────────────────────────────────────────────────────────

    private record ClipEntry(String text, Instant time) {}
}
