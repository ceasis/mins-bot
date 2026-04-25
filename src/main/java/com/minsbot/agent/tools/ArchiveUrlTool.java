package com.minsbot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Save a URL to the research archive for later. Fetches the page text,
 * stores it as a markdown file alongside research syntheses so it shows
 * up in /research.html, findAnything, and todaysFocus context.
 */
@Component
public class ArchiveUrlTool {

    private static final Logger log = LoggerFactory.getLogger(ArchiveUrlTool.class);
    private static final Path DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "research_archive");
    private static final DateTimeFormatter FS_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final PlaywrightService playwright;
    private final ToolExecutionNotifier notifier;

    public ArchiveUrlTool(PlaywrightService playwright, ToolExecutionNotifier notifier) {
        this.playwright = playwright;
        this.notifier = notifier;
    }

    @Tool(description = "Save a webpage to the research archive for later recall. Fetches the page text "
            + "and stores it as a timestamped archive entry. Use when the user says 'save this for later', "
            + "'archive this URL', 'remember this page', 'bookmark <url>'. Archived items appear in "
            + "/research.html and are searchable via findAnything.")
    public String archiveUrl(
            @ToolParam(description = "The URL to fetch and archive.") String url,
            @ToolParam(description = "Optional short note about why this is being saved.", required = false) String note) {
        if (url == null || url.isBlank()) return "Error: url is required.";
        url = url.trim();
        if (!url.startsWith("http")) url = "https://" + url;
        notifier.notify("📥 archiving: " + url);
        String text;
        try {
            text = playwright.getPageText(url);
        } catch (Exception e) {
            return "Failed to fetch " + url + ": " + e.getMessage();
        }
        if (text == null || text.isBlank()) return "Fetched but page returned no text: " + url;

        try {
            Files.createDirectories(DIR);
            String stem = LocalDateTime.now().format(FS_TS);
            Path p = DIR.resolve(stem + ".md");
            StringBuilder body = new StringBuilder();
            body.append("# Saved page · ").append(url).append("\n\n");
            body.append("_archived: ").append(LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append("_\n\n");
            if (note != null && !note.isBlank()) body.append("**Note:** ").append(note.trim()).append("\n\n");
            body.append("**Source:** ").append(url).append("\n\n");
            body.append("── content ──\n\n").append(truncate(text, 20000));
            Files.writeString(p, body.toString(), StandardCharsets.UTF_8);
            return "✅ Archived: /research/" + stem + " (" + p.getFileName() + ")";
        } catch (Exception e) {
            log.warn("[ArchiveUrl] write failed: {}", e.getMessage());
            return "Failed to archive: " + e.getMessage();
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n\n…(truncated)";
    }
}
