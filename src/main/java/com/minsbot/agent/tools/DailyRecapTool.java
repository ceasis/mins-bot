package com.minsbot.agent.tools;

import com.minsbot.TranscriptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * End-of-day companion to {@link DailyBriefingTool}. "What did I do today?"
 * Aggregates today's notes, recent conversation highlights, and any files
 * saved under mins_bot_data today into a concise recap.
 */
@Component
public class DailyRecapTool {

    private static final Logger log = LoggerFactory.getLogger(DailyRecapTool.class);
    private static final Path NOTES_DIR = Paths.get(System.getProperty("user.home"), "mins_bot_data", "quick_notes");
    private static final Path DATA_DIR = Paths.get(System.getProperty("user.home"), "mins_bot_data");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm");

    @Autowired(required = false) private TranscriptService transcriptService;
    @Autowired(required = false) private ToolExecutionNotifier notifier;

    @Tool(description = "End-of-day recap / daily wrap-up / 'what did I do today'. Summarizes "
            + "today's saved notes, recent conversation highlights, and files created today "
            + "under mins_bot_data. Use for 'recap my day', 'wrap up', 'daily summary', "
            + "'end of day report', 'what did I accomplish'.")
    public String dailyRecap() {
        if (notifier != null) notifier.notify("📊 building your daily recap...");
        LocalDate today = LocalDate.now();
        String header = "── recap · " + today.format(DateTimeFormatter.ofPattern("EEEE, MMM d")) + " ──";
        StringBuilder out = new StringBuilder(header).append("\n\n");

        // 1) Notes saved today
        out.append("📝 Notes captured today:\n");
        List<String> notes = notesForDay(today);
        if (notes.isEmpty()) out.append("  (none)\n");
        else for (String n : notes) out.append("  • ").append(n).append("\n");

        // 2) Files modified under mins_bot_data today (code projects, reports)
        out.append("\n📁 Work touched today (under mins_bot_data/):\n");
        List<String> files = recentFilesForDay(today, 12);
        if (files.isEmpty()) out.append("  (none)\n");
        else for (String f : files) out.append("  • ").append(f).append("\n");

        // 3) Conversation volume
        if (transcriptService != null) {
            try {
                int sz = transcriptService.getRecentMemory().size();
                out.append("\n💬 Chat buffer: ").append(sz).append(" message(s) in recent memory\n");
            } catch (Exception ignored) {}
        }

        out.append("\n── end recap ──");
        return out.toString();
    }

    private static List<String> notesForDay(LocalDate day) {
        List<String> result = new ArrayList<>();
        if (!Files.isDirectory(NOTES_DIR)) return result;
        String prefix = day.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        try (Stream<Path> s = Files.list(NOTES_DIR)) {
            s.filter(p -> p.getFileName().toString().startsWith(prefix))
             .sorted()
             .forEach(p -> {
                 try {
                     String body = Files.readString(p, StandardCharsets.UTF_8).trim();
                     String stem = p.getFileName().toString().replace(".txt", "");
                     String t = stem.length() >= 15 ? stem.substring(9, 11) + ":" + stem.substring(11, 13) : "";
                     int nl = body.indexOf('\n');
                     String line = nl < 0 ? body : body.substring(0, nl);
                     if (line.length() > 140) line = line.substring(0, 140) + "…";
                     result.add(t + " — " + line);
                 } catch (IOException ignored) {}
             });
        } catch (IOException ignored) {}
        return result;
    }

    private static List<String> recentFilesForDay(LocalDate day, int max) {
        List<String> result = new ArrayList<>();
        if (!Files.isDirectory(DATA_DIR)) return result;
        long startOfDay = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long endOfDay = day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        try (Stream<Path> s = Files.walk(DATA_DIR, 3)) {
            s.filter(Files::isRegularFile)
             .filter(p -> {
                 try {
                     long m = Files.getLastModifiedTime(p).toMillis();
                     return m >= startOfDay && m < endOfDay;
                 } catch (IOException e) { return false; }
             })
             .sorted((a, b) -> {
                 try { return Long.compare(Files.getLastModifiedTime(b).toMillis(),
                                            Files.getLastModifiedTime(a).toMillis()); }
                 catch (IOException e) { return 0; }
             })
             .limit(max)
             .forEach(p -> {
                 try {
                     long m = Files.getLastModifiedTime(p).toMillis();
                     String t = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(m),
                             ZoneId.systemDefault()).format(TS);
                     result.add(t + " — " + DATA_DIR.relativize(p).toString().replace('\\', '/'));
                 } catch (IOException ignored) {}
             });
        } catch (IOException ignored) {}
        return result;
    }
}
