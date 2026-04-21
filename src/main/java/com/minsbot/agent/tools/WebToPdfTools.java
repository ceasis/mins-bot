package com.minsbot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Save any webpage as a PDF via headless Chromium (Playwright). Great for
 * archiving articles, receipts, online documentation, or recipes.
 */
@Component
public class WebToPdfTools {

    private static final Logger log = LoggerFactory.getLogger(WebToPdfTools.class);

    private final PlaywrightService playwright;
    private final ToolExecutionNotifier notifier;

    public WebToPdfTools(PlaywrightService playwright, ToolExecutionNotifier notifier) {
        this.playwright = playwright;
        this.notifier = notifier;
    }

    @Tool(description = "Save a webpage as a PDF file using headless Chromium. "
            + "USE THIS when the user says 'save this article as a PDF', 'archive this page', "
            + "'print this page to PDF', 'save this recipe / receipt / tutorial as a PDF', 'download page as PDF'. "
            + "Renders with background colors and images preserved.")
    public String savePageAsPdf(
            @ToolParam(description = "URL of the page to save") String url,
            @ToolParam(description = "Absolute path for the output PDF (e.g. C:/Users/me/Desktop/article.pdf)") String outputPath,
            @ToolParam(description = "Page format: 'Letter' (default), 'A4', 'A3', 'Legal', 'Tabloid'") String format,
            @ToolParam(description = "Landscape orientation? true/false (default false)") boolean landscape) {
        if (url == null || url.isBlank()) return "Provide a URL.";
        if (outputPath == null || outputPath.isBlank()) return "Provide an output path.";
        String target = url.trim();
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            target = "https://" + target;
        }
        notifier.notify("Rendering " + target + " → PDF...");
        try {
            String saved = playwright.renderToPdf(target, outputPath.trim(), format, landscape);
            long bytes = Files.size(Path.of(saved));
            return "✅ Saved as PDF: " + saved + " (" + bytes + " bytes)";
        } catch (Exception e) {
            log.warn("[WebToPdf] render failed: {}", e.getMessage());
            return "Render failed: " + e.getMessage();
        }
    }
}
