package com.minsbot.agent.tools;

import com.minsbot.TranscriptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * AI-callable tools for exporting chat conversation history to Markdown or HTML files.
 */
@Component
public class ExportTools {

    private static final Logger log = LoggerFactory.getLogger(ExportTools.class);
    private static final Path BASE_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "exports");
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final TranscriptService transcriptService;
    private final ToolExecutionNotifier notifier;

    public ExportTools(TranscriptService transcriptService, ToolExecutionNotifier notifier) {
        this.transcriptService = transcriptService;
        this.notifier = notifier;
    }

    @Tool(description = "Export the conversation history to a Markdown (.md) file. " +
            "Saves to ~/mins_bot_data/exports/. Returns the file path.")
    public String exportToMarkdown() {
        notifier.notify("Exporting chat to Markdown");
        try {
            Files.createDirectories(BASE_DIR);
            List<Map<String, Object>> history = transcriptService.getStructuredHistory();

            if (history.isEmpty()) return "No conversation history to export.";

            StringBuilder md = new StringBuilder();
            md.append("# Mins Bot Chat Export\n");
            md.append("**Exported:** ").append(LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n\n");
            md.append("---\n\n");

            for (Map<String, Object> entry : history) {
                boolean isUser = (Boolean) entry.get("isUser");
                String text = (String) entry.get("text");
                String speaker = isUser ? "**You**" : "**Mins Bot**";
                md.append(speaker).append(": ").append(text).append("\n\n");
            }

            String filename = "chat_" + LocalDateTime.now().format(TS_FMT) + ".md";
            Path target = BASE_DIR.resolve(filename);
            Files.writeString(target, md.toString());

            log.info("[Export] Saved Markdown: {}", target);
            return "Chat exported to Markdown: " + target.toAbsolutePath()
                    + " (" + history.size() + " messages)";
        } catch (Exception e) {
            log.error("[Export] Markdown export failed: {}", e.getMessage());
            return "Export failed: " + e.getMessage();
        }
    }

    @Tool(description = "Export the conversation history to an HTML file with styled formatting. " +
            "Saves to ~/mins_bot_data/exports/. Can be opened in any browser.")
    public String exportToHtml() {
        notifier.notify("Exporting chat to HTML");
        try {
            Files.createDirectories(BASE_DIR);
            List<Map<String, Object>> history = transcriptService.getStructuredHistory();

            if (history.isEmpty()) return "No conversation history to export.";

            StringBuilder html = new StringBuilder();
            html.append("""
                    <!DOCTYPE html>
                    <html><head><meta charset="UTF-8">
                    <title>Mins Bot Chat Export</title>
                    <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
                           max-width: 800px; margin: 0 auto; padding: 20px; background: #1a1a2e; color: #eee; }
                    h1 { color: #00d4ff; }
                    .msg { padding: 10px 15px; margin: 8px 0; border-radius: 12px; max-width: 80%; }
                    .user { background: #16213e; margin-left: auto; text-align: right; }
                    .bot { background: #0f3460; }
                    .speaker { font-weight: bold; font-size: 0.8em; color: #00d4ff; margin-bottom: 4px; }
                    .meta { color: #666; font-size: 0.85em; text-align: center; margin-top: 20px; }
                    </style></head><body>
                    <h1>Mins Bot Chat Export</h1>
                    <p class="meta">Exported: %s</p>
                    """.formatted(LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));

            for (Map<String, Object> entry : history) {
                boolean isUser = (Boolean) entry.get("isUser");
                String text = (String) entry.get("text");
                String cssClass = isUser ? "user" : "bot";
                String speaker = isUser ? "You" : "Mins Bot";
                // Escape HTML
                text = text.replace("&", "&amp;").replace("<", "&lt;")
                        .replace(">", "&gt;").replace("\n", "<br>");
                html.append("<div class=\"msg ").append(cssClass).append("\">");
                html.append("<div class=\"speaker\">").append(speaker).append("</div>");
                html.append(text).append("</div>\n");
            }

            html.append("<p class=\"meta\">").append(history.size())
                    .append(" messages</p></body></html>");

            String filename = "chat_" + LocalDateTime.now().format(TS_FMT) + ".html";
            Path target = BASE_DIR.resolve(filename);
            Files.writeString(target, html.toString());

            log.info("[Export] Saved HTML: {}", target);
            return "Chat exported to HTML: " + target.toAbsolutePath()
                    + " (" + history.size() + " messages)";
        } catch (Exception e) {
            log.error("[Export] HTML export failed: {}", e.getMessage());
            return "Export failed: " + e.getMessage();
        }
    }

    @Tool(description = "Export conversation history as plain text. Returns the text directly " +
            "rather than saving to a file.")
    public String exportToText(
            @ToolParam(description = "Maximum number of messages to include (0 = all)") int maxMessages) {
        notifier.notify("Exporting chat as text");
        try {
            List<Map<String, Object>> history = transcriptService.getStructuredHistory();
            if (history.isEmpty()) return "No conversation history.";

            if (maxMessages > 0 && maxMessages < history.size()) {
                history = history.subList(history.size() - maxMessages, history.size());
            }

            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> entry : history) {
                boolean isUser = (Boolean) entry.get("isUser");
                String text = (String) entry.get("text");
                sb.append(isUser ? "USER: " : "BOT: ").append(text).append("\n");
            }

            String result = sb.toString().trim();
            if (result.length() > 10000) {
                result = result.substring(0, 10000) + "\n... (truncated)";
            }
            return result;
        } catch (Exception e) {
            return "Export failed: " + e.getMessage();
        }
    }
}
