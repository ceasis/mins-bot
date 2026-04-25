package com.minsbot.agent.tools;

import com.minsbot.TranscriptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;

/**
 * One-shot "find anything you've ever told me" tool. Searches quick-notes,
 * recent conversation memory, and persisted chat history in a single call
 * and returns a unified result block. The LLM should prefer this over
 * chaining searchNotes + recallRecentConversation + searchPastConversations
 * when the user asks an open-ended recall question.
 */
@Component
public class UnifiedFindTool {

    private static final Logger log = LoggerFactory.getLogger(UnifiedFindTool.class);
    private static final Path NOTES_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "quick_notes");
    private static final Path RESEARCH_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "research_archive");

    @Autowired(required = false) private TranscriptService transcriptService;
    @Autowired(required = false) private ToolExecutionNotifier notifier;

    @Tool(description = "Find anything across the user's personal data: quick notes, recent chat memory, "
            + "and past conversation history — all in one call. Use for open-ended recall like "
            + "'what do I know about X', 'did I ever mention Y', 'find anything about Z'. "
            + "PREFER this over chaining searchNotes + recallRecentConversation manually.")
    public String findAnything(
            @ToolParam(description = "Keyword or phrase to search for (case-insensitive).") String query) {
        if (query == null || query.isBlank()) return "Error: query is required.";
        if (notifier != null) notifier.notify("🔎 searching everywhere: " + query);
        String q = query.toLowerCase().trim();
        StringBuilder out = new StringBuilder();
        int totalHits = 0;

        // Notes
        int noteHits = 0;
        StringBuilder notesSb = new StringBuilder();
        if (Files.isDirectory(NOTES_DIR)) {
            try (Stream<Path> s = Files.list(NOTES_DIR)) {
                List<Path> files = s.filter(p -> p.toString().endsWith(".txt")).sorted().toList();
                for (Path p : files) {
                    try {
                        String body = Files.readString(p, StandardCharsets.UTF_8);
                        if (body.toLowerCase().contains(q)) {
                            noteHits++;
                            String stem = p.getFileName().toString().replace(".txt", "");
                            notesSb.append("  • [").append(stem).append("] ")
                                   .append(trimFor(body, 160)).append("\n");
                        }
                    } catch (IOException ignored) {}
                }
            } catch (IOException ignored) {}
        }
        if (noteHits > 0) {
            out.append("📝 Notes (").append(noteHits).append("):\n").append(notesSb).append("\n");
            totalHits += noteHits;
        }

        // Research archive
        int researchHits = 0;
        StringBuilder researchSb = new StringBuilder();
        if (Files.isDirectory(RESEARCH_DIR)) {
            try (Stream<Path> s = Files.list(RESEARCH_DIR)) {
                List<Path> files = s.filter(p -> p.toString().endsWith(".md"))
                        .sorted(java.util.Comparator.reverseOrder()).toList();
                for (Path p : files) {
                    try {
                        String body = Files.readString(p, StandardCharsets.UTF_8);
                        if (body.toLowerCase().contains(q)) {
                            researchHits++;
                            String stem = p.getFileName().toString().replace(".md", "");
                            String firstLine = body.split("\\R", 2)[0].replace("#", "").trim();
                            researchSb.append("  • [").append(stem).append("] ")
                                      .append(trimFor(firstLine, 140)).append("\n");
                        }
                    } catch (IOException ignored) {}
                }
            } catch (IOException ignored) {}
        }
        if (researchHits > 0) {
            out.append("🔎 Research archive (").append(researchHits).append("):\n")
               .append(researchSb).append("\n");
            totalHits += researchHits;
        }

        // Recent chat memory
        if (transcriptService != null) {
            try {
                List<String> recent = transcriptService.searchMemory(query);
                if (!recent.isEmpty()) {
                    int show = Math.min(recent.size(), 8);
                    out.append("💬 Recent chat (").append(recent.size()).append("):\n");
                    for (int i = 0; i < show; i++) out.append("  • ").append(trimFor(recent.get(i), 200)).append("\n");
                    if (recent.size() > show) out.append("  (").append(recent.size() - show).append(" more)\n");
                    out.append("\n");
                    totalHits += recent.size();
                }
            } catch (Exception ignored) {}

            // Persisted history
            try {
                List<String> past = transcriptService.searchHistoryFiles(query, 30);
                if (!past.isEmpty()) {
                    int show = Math.min(past.size(), 8);
                    out.append("📚 Past conversations (").append(past.size()).append("):\n");
                    for (int i = 0; i < show; i++) out.append("  • ").append(trimFor(past.get(i), 200)).append("\n");
                    if (past.size() > show) out.append("  (").append(past.size() - show).append(" more)\n");
                    out.append("\n");
                    totalHits += past.size();
                }
            } catch (Exception ignored) {}
        }

        if (totalHits == 0) return "Nothing found for \"" + query + "\" across notes, recent chat, or history.";
        return "Found " + totalHits + " mention(s) of \"" + query + "\":\n\n" + out;
    }

    private static String trimFor(String s, int max) {
        if (s == null) return "";
        s = s.replace("\n", " · ").trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
