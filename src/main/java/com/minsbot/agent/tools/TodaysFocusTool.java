package com.minsbot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * "What should I focus on today?" — one-line synthesis grounded in the
 * user's actual recent notes + research. Distinct from {@link WhatNowTool}
 * (which is mechanical: next event, latest notes) and {@link DailyBriefingTool}
 * (which is exhaustive: gmail+calendar+weather). This is editorial: an
 * opinionated single-sentence answer.
 */
@Component
public class TodaysFocusTool {

    private static final Logger log = LoggerFactory.getLogger(TodaysFocusTool.class);
    private static final Path NOTES_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "quick_notes");
    private static final Path RESEARCH_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "research_archive");

    private final ChatClient chatClient;
    private final ToolExecutionNotifier notifier;

    public TodaysFocusTool(ChatClient.Builder chatClientBuilder, ToolExecutionNotifier notifier) {
        this.chatClient = chatClientBuilder.build();
        this.notifier = notifier;
    }

    @Tool(description = "Editorial 'what should I focus on today?' — synthesizes a single-sentence "
            + "focus recommendation grounded in the user's most recent notes and research. Distinct "
            + "from dailyBriefing (exhaustive) and whatNow (mechanical). Use for 'what should I focus on', "
            + "'pick my priority', 'tell me what matters today'.")
    public String todaysFocus() {
        if (notifier != null) notifier.notify("🎯 picking your focus...");
        StringBuilder ctx = new StringBuilder();
        List<String> notes = readRecent(NOTES_DIR, ".txt", 8);
        List<String> research = readRecent(RESEARCH_DIR, ".md", 5);

        if (notes.isEmpty() && research.isEmpty()) {
            return "No recent notes or research to ground a recommendation. Capture a few notes first ('note: …') or run a research query.";
        }

        ctx.append("RECENT NOTES (newest first):\n");
        for (String n : notes) ctx.append("- ").append(trim(n, 200)).append("\n");
        ctx.append("\nRECENT RESEARCH TOPICS:\n");
        for (String r : research) ctx.append("- ").append(trim(r, 200)).append("\n");

        String prompt = "You are the user's personal assistant. Based ONLY on the recent notes and "
                + "research below, write ONE sentence (max 30 words) recommending the single most "
                + "important thing for them to focus on today. Be specific and reference an actual "
                + "item from their data — don't be generic. If the data is too sparse, say so.\n\n"
                + ctx;
        try {
            String reply = chatClient.prompt().user(prompt).call().content();
            if (reply == null || reply.isBlank()) reply = "(no focus suggestion returned)";
            return "🎯 " + reply.trim();
        } catch (Exception e) {
            log.warn("[TodaysFocus] failed: {}", e.getMessage());
            return "Focus suggestion unavailable: " + e.getMessage();
        }
    }

    private static List<String> readRecent(Path dir, String ext, int max) {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.toString().endsWith(ext))
             .sorted(Comparator.reverseOrder())
             .limit(max)
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
