package com.minsbot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Quick-capture notes. The user says "remember that I parked on level 4",
 * "note: the code is 4472", "jot down: call Tim tomorrow" — and it lands
 * in a timestamped file under mins_bot_data/quick_notes/. Listable,
 * searchable, deletable. This is the missing "second brain" primitive.
 */
@Component
public class QuickNotesTool {

    private static final Logger log = LoggerFactory.getLogger(QuickNotesTool.class);
    private static final Path DIR = Paths.get(System.getProperty("user.home"), "mins_bot_data", "quick_notes");
    private static final DateTimeFormatter FS_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter HUMAN_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ToolExecutionNotifier notifier;
    private final org.springframework.ai.chat.client.ChatClient chatClient;

    public QuickNotesTool(ToolExecutionNotifier notifier,
                          org.springframework.ai.chat.client.ChatClient.Builder chatClientBuilder) {
        this.notifier = notifier;
        this.chatClient = chatClientBuilder.build();
        try { Files.createDirectories(DIR); } catch (IOException ignored) {}
    }

    private String suggestTags(String text) {
        try {
            String prompt = "Suggest 1-3 short lowercase hashtag categories for this personal note. "
                    + "Reply with ONLY the hashtags, space-separated, e.g. '#wifi #cabin'. No prose. "
                    + "If nothing fits, reply 'none'.\n\nNote: " + text;
            String reply = chatClient.prompt().user(prompt).call().content();
            if (reply == null) return "";
            reply = reply.trim();
            if (reply.equalsIgnoreCase("none") || reply.isEmpty()) return "";
            // Keep only hashtag-shaped tokens
            StringBuilder out = new StringBuilder();
            for (String tok : reply.split("\\s+")) {
                if (tok.matches("#[a-z0-9_-]{2,20}")) {
                    if (out.length() > 0) out.append(' ');
                    out.append(tok);
                }
            }
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }

    @Tool(description = "Save a quick note / jot something down / remember a fact. Use when the user says "
            + "'remember that X', 'note: X', 'jot down X', 'save this: X', 'don't let me forget X'. "
            + "Stores a timestamped note on disk; retrievable via listNotes or searchNotes.")
    public String saveNote(
            @ToolParam(description = "The note text to remember, in the user's own words.") String text) {
        if (text == null || text.isBlank()) return "Error: note text is required.";
        LocalDateTime now = LocalDateTime.now();
        String fname = now.format(FS_TS) + ".txt";
        Path p = DIR.resolve(fname);
        String body = text.trim();
        // Auto-tag only if user hasn't already provided any hashtag.
        String autoTags = body.contains("#") ? "" : suggestTags(body);
        if (!autoTags.isEmpty()) body = body + "  " + autoTags;
        try {
            Files.writeString(p, body + "\n", StandardCharsets.UTF_8);
            notifier.notify("📝 note saved" + (autoTags.isEmpty() ? "" : " " + autoTags));
            return "Saved: " + fname + " — \"" + trimFor(text, 80) + "\""
                    + (autoTags.isEmpty() ? "" : " (auto-tagged " + autoTags + ")");
        } catch (IOException e) {
            log.warn("[QuickNotes] save failed: {}", e.getMessage());
            return "Failed to save note: " + e.getMessage();
        }
    }

    @Tool(description = "Filter notes by hashtag (e.g. '#wifi', '#car', '#groceries'). Tags are inferred "
            + "from the note body — any '#word' counts. Use when the user asks 'show my #wifi notes', "
            + "'all notes tagged X'.")
    public String notesByTag(
            @ToolParam(description = "Tag to filter by, with or without leading '#' (e.g. 'wifi' or '#wifi').") String tag) {
        if (tag == null || tag.isBlank()) return "Error: tag is required.";
        String needle = "#" + tag.replace("#", "").trim().toLowerCase();
        List<Path> files = listFiles();
        StringBuilder sb = new StringBuilder();
        int hits = 0;
        for (Path p : files) {
            try {
                String body = Files.readString(p, StandardCharsets.UTF_8);
                if (body.toLowerCase().contains(needle)) {
                    hits++;
                    sb.append("• ").append(humanStamp(p.getFileName().toString())).append(" — ")
                      .append(body.trim()).append("\n\n");
                }
            } catch (IOException ignored) {}
        }
        if (hits == 0) return "No notes tagged " + needle + ".";
        return "📝 " + hits + " note(s) tagged " + needle + ":\n\n" + sb;
    }

    @Tool(description = "List all hashtags found across saved notes, with counts. Use when the user asks "
            + "'what tags do I have', 'what categories', 'show note tags'.")
    public String listTags() {
        List<Path> files = listFiles();
        java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
        java.util.regex.Pattern tagP = java.util.regex.Pattern.compile("#([A-Za-z0-9_-]{2,})");
        for (Path p : files) {
            try {
                String body = Files.readString(p, StandardCharsets.UTF_8);
                java.util.regex.Matcher m = tagP.matcher(body);
                java.util.Set<String> seen = new java.util.HashSet<>();
                while (m.find()) seen.add("#" + m.group(1).toLowerCase());
                for (String t : seen) counts.merge(t, 1, Integer::sum);
            } catch (IOException ignored) {}
        }
        if (counts.isEmpty()) return "No tags yet. Add hashtags inside notes (e.g. '#wifi').";
        StringBuilder sb = new StringBuilder("🏷  Tags (" + counts.size() + "):\n");
        counts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> sb.append("  ").append(e.getKey()).append("  × ").append(e.getValue()).append("\n"));
        return sb.toString();
    }

    @Tool(description = "Pin or unpin a note (adds/removes '#pinned' inside the note body). "
            + "Use when the user says 'pin this note', 'unpin', 'always show this'. "
            + "Pinned notes appear at the top of listNotes.")
    public String togglePin(
            @ToolParam(description = "Note id (filename stem like 20260425-114300).") String id) {
        if (id == null || id.isBlank()) return "Error: note id required.";
        String fname = id.endsWith(".txt") ? id : id + ".txt";
        Path p = DIR.resolve(fname).normalize();
        if (!p.startsWith(DIR) || !Files.isRegularFile(p)) return "Note not found: " + id;
        try {
            String body = Files.readString(p, StandardCharsets.UTF_8);
            String updated;
            boolean pinned;
            if (body.toLowerCase().contains("#pinned")) {
                updated = body.replaceAll("(?i)\\s*#pinned\\b", "").trim() + "\n";
                pinned = false;
            } else {
                updated = body.trim() + " #pinned\n";
                pinned = true;
            }
            Files.writeString(p, updated, StandardCharsets.UTF_8);
            return (pinned ? "📌 Pinned: " : "🔓 Unpinned: ") + id;
        } catch (IOException e) {
            return "Failed: " + e.getMessage();
        }
    }

    @Tool(description = "List quick notes, most recent first. Pinned notes (containing '#pinned') "
            + "appear at the top regardless of date. Use when the user asks 'show my notes', "
            + "'what did I jot down', 'list notes', 'what am I supposed to remember'.")
    public String listNotes(
            @ToolParam(description = "Maximum number of notes to show (default 20).", required = false) Integer limit) {
        int max = (limit == null || limit <= 0) ? 20 : limit;
        List<Path> files = listFiles();
        if (files.isEmpty()) return "No notes saved yet.";
        List<Path> pinned = new ArrayList<>();
        List<Path> rest = new ArrayList<>();
        for (Path p : files) {
            try {
                if (Files.readString(p, StandardCharsets.UTF_8).toLowerCase().contains("#pinned")) pinned.add(p);
                else rest.add(p);
            } catch (IOException e) { rest.add(p); }
        }
        StringBuilder sb = new StringBuilder("📝 ").append(files.size()).append(" note(s):\n\n");
        int shown = 0;
        if (!pinned.isEmpty()) {
            sb.append("📌 Pinned:\n");
            for (Path p : pinned) {
                if (shown++ >= max) break;
                sb.append("• ").append(humanStamp(p.getFileName().toString())).append(" — ")
                  .append(firstLine(p)).append("\n");
            }
            sb.append("\n");
        }
        for (Path p : rest) {
            if (shown++ >= max) break;
            sb.append("• ").append(humanStamp(p.getFileName().toString())).append(" — ")
              .append(firstLine(p)).append("\n");
        }
        if (files.size() > max) sb.append("\n(").append(files.size() - max).append(" more not shown)");
        return sb.toString();
    }

    @Tool(description = "Search quick notes for a keyword or phrase. Use when the user asks "
            + "'what did I note about X', 'find my note on Y', 'did I write something down about Z'.")
    public String searchNotes(
            @ToolParam(description = "Keyword or phrase to search for (case-insensitive substring match).") String query) {
        if (query == null || query.isBlank()) return "Error: query is required.";
        String q = query.toLowerCase().trim();
        List<Path> files = listFiles();
        StringBuilder sb = new StringBuilder();
        int hits = 0;
        for (Path p : files) {
            try {
                String body = Files.readString(p, StandardCharsets.UTF_8);
                if (body.toLowerCase().contains(q)) {
                    sb.append("• ").append(humanStamp(p.getFileName().toString())).append(" — ")
                      .append(body.trim()).append("\n\n");
                    hits++;
                }
            } catch (IOException ignored) {}
        }
        if (hits == 0) return "No notes match \"" + query + "\".";
        return "📝 " + hits + " match(es):\n\n" + sb;
    }

    private static final Path TRASH_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "quick_notes_trash");

    @Tool(description = "Delete a quick note by its id (filename stem like 20260425-062800). "
            + "Use when the user says 'delete that note', 'forget that'. Soft-delete: the note "
            + "moves to a trash folder and can be recovered with restoreNote.")
    public String deleteNote(
            @ToolParam(description = "Note id — the filename stem returned from saveNote or listNotes.") String id) {
        if (id == null || id.isBlank()) return "Error: note id is required.";
        String fname = id.endsWith(".txt") ? id : id + ".txt";
        Path p = DIR.resolve(fname).normalize();
        if (!p.startsWith(DIR)) return "Error: invalid id.";
        try {
            if (!Files.isRegularFile(p)) return "Note not found: " + id;
            Files.createDirectories(TRASH_DIR);
            Files.move(p, TRASH_DIR.resolve(fname), StandardCopyOption.REPLACE_EXISTING);
            return "Deleted: " + id + " (recoverable via restoreNote)";
        } catch (IOException e) {
            return "Failed to delete: " + e.getMessage();
        }
    }

    @Tool(description = "Restore a previously deleted note from trash. Use when the user says "
            + "'undelete that note', 'restore my note', 'I deleted it by mistake'.")
    public String restoreNote(
            @ToolParam(description = "Note id to restore (filename stem).") String id) {
        if (id == null || id.isBlank()) return "Error: note id required.";
        String fname = id.endsWith(".txt") ? id : id + ".txt";
        Path src = TRASH_DIR.resolve(fname).normalize();
        if (!src.startsWith(TRASH_DIR) || !Files.isRegularFile(src)) return "Not in trash: " + id;
        try {
            Files.move(src, DIR.resolve(fname), StandardCopyOption.REPLACE_EXISTING);
            return "Restored: " + id;
        } catch (IOException e) {
            return "Failed to restore: " + e.getMessage();
        }
    }

    @Tool(description = "List notes currently in trash (deleted but recoverable). Use when the user "
            + "asks 'what did I delete', 'show trash', 'recently deleted notes'.")
    public String listTrash() {
        if (!Files.isDirectory(TRASH_DIR)) return "Trash is empty.";
        try (Stream<Path> s = Files.list(TRASH_DIR)) {
            List<Path> files = s.filter(p -> p.toString().endsWith(".txt"))
                    .sorted(Comparator.reverseOrder()).toList();
            if (files.isEmpty()) return "Trash is empty.";
            StringBuilder sb = new StringBuilder("🗑  ").append(files.size()).append(" in trash:\n");
            for (Path p : files) {
                sb.append("• ").append(humanStamp(p.getFileName().toString())).append(" — ")
                  .append(firstLine(p)).append("\n");
            }
            return sb.toString();
        } catch (IOException e) { return "Failed to read trash: " + e.getMessage(); }
    }

    private static List<Path> listFiles() {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(DIR)) return out;
        try (Stream<Path> s = Files.list(DIR)) {
            s.filter(p -> p.toString().endsWith(".txt"))
             .sorted(Comparator.reverseOrder())
             .forEach(out::add);
        } catch (IOException ignored) {}
        return out;
    }

    private static String firstLine(Path p) {
        try {
            String body = Files.readString(p, StandardCharsets.UTF_8).trim();
            int nl = body.indexOf('\n');
            return trimFor(nl < 0 ? body : body.substring(0, nl), 120);
        } catch (IOException e) { return "(unreadable)"; }
    }

    private static String humanStamp(String fname) {
        String stem = fname.endsWith(".txt") ? fname.substring(0, fname.length() - 4) : fname;
        try {
            LocalDateTime t = LocalDateTime.parse(stem, FS_TS);
            return t.format(HUMAN_TS);
        } catch (Exception e) { return stem; }
    }

    private static String trimFor(String s, int max) {
        s = s.replace("\n", " ").trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
