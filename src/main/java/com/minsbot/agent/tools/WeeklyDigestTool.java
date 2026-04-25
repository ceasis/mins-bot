package com.minsbot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Weekly digest. "Show me my week" — synthesizes notes + research from
 * the last 7 days into a Sunday-style review. Complements the daily
 * briefing/recap with a longer reflection horizon.
 */
@Component
public class WeeklyDigestTool {

    private static final Logger log = LoggerFactory.getLogger(WeeklyDigestTool.class);
    private static final Path NOTES_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "quick_notes");
    private static final Path RESEARCH_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "research_archive");
    private static final DateTimeFormatter FS_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ChatClient chatClient;
    private final ToolExecutionNotifier notifier;

    public WeeklyDigestTool(ChatClient.Builder builder, ToolExecutionNotifier notifier) {
        this.chatClient = builder.build();
        this.notifier = notifier;
    }

    @Tool(description = "Weekly digest / 'my week in review' / 'show me the past 7 days'. Synthesizes "
            + "notes and research from the last week into a reflective summary. Use for 'weekly review', "
            + "'sunday digest', 'what happened this week'.")
    public String weeklyDigest() {
        notifier.notify("📊 building weekly digest...");
        LocalDate cutoff = LocalDate.now().minusDays(7);
        List<String> notes = collect(NOTES_DIR, ".txt", cutoff);
        List<String> research = collect(RESEARCH_DIR, ".md", cutoff);

        if (notes.isEmpty() && research.isEmpty()) {
            return "Nothing captured in the last 7 days. Add a few notes or run a research query first.";
        }

        StringBuilder ctx = new StringBuilder();
        ctx.append("NOTES THIS WEEK (").append(notes.size()).append("):\n");
        for (String n : notes) ctx.append("- ").append(trim(n, 250)).append("\n");
        ctx.append("\nRESEARCH THIS WEEK (").append(research.size()).append("):\n");
        for (String r : research) ctx.append("- ").append(trim(r, 250)).append("\n");

        String prompt = "You are the user's personal assistant. Below is everything they captured "
                + "in the last 7 days. Write a short reflective weekly digest (max 200 words) covering: "
                + "(1) main themes, (2) notable items worth revisiting, (3) one suggestion for next week. "
                + "Be specific — reference actual items. Skip generic advice.\n\n" + ctx;
        try {
            String reply = chatClient.prompt().user(prompt).call().content();
            if (reply == null || reply.isBlank()) reply = "(no digest produced)";
            String header = "── weekly digest · " + LocalDate.now().format(
                    DateTimeFormatter.ofPattern("MMM d")) + " ──\n\n";
            return header + reply.trim() + "\n\n(based on " + notes.size() + " note(s) + "
                    + research.size() + " research item(s))";
        } catch (Exception e) {
            log.warn("[WeeklyDigest] failed: {}", e.getMessage());
            return "Weekly digest unavailable: " + e.getMessage();
        }
    }

    private static List<String> collect(Path dir, String ext, LocalDate cutoff) {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.toString().endsWith(ext))
             .filter(p -> {
                 String stem = p.getFileName().toString().replace(ext, "");
                 try {
                     return LocalDateTime.parse(stem, FS_TS).toLocalDate().isAfter(cutoff.minusDays(1));
                 } catch (Exception e) { return false; }
             })
             .sorted(Comparator.reverseOrder())
             .forEach(p -> {
                 try {
                     String body = Files.readString(p, StandardCharsets.UTF_8).trim();
                     int nl = body.indexOf('\n');
                     out.add(nl < 0 ? body : body.substring(0, nl));
                 } catch (IOException ignored) {}
             });
        } catch (IOException ignored) {}
        return out;
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        s = s.replace("\n", " ").trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
