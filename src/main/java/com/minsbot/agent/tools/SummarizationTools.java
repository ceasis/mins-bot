package com.minsbot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.stream.Collectors;

/**
 * AI-callable tool that summarizes directive findings using the AI model itself.
 * Reads all text files from a directive's data folder and asks the model to summarize.
 */
@Component
public class SummarizationTools {

    private static final Logger log = LoggerFactory.getLogger(SummarizationTools.class);
    private static final Path BASE_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data");

    private final ToolExecutionNotifier notifier;

    @Autowired(required = false)
    private ChatClient chatClient;

    public SummarizationTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @Tool(description = "Summarize all findings gathered for a specific directive. " +
            "Reads all text files in the directive's data folder and produces a concise summary. " +
            "Use after gathering data for a directive to get a digestible overview.")
    public String summarizeDirective(
            @ToolParam(description = "The directive name whose findings to summarize") String directiveName) {
        notifier.notify("Summarizing: " + directiveName);
        try {
            String safeName = sanitizeName(directiveName);
            Path dir = BASE_DIR.resolve("directive_" + safeName);

            if (!Files.isDirectory(dir)) {
                return "No data folder found for directive: " + directiveName;
            }

            // Collect all text content from .txt files
            StringBuilder allText = new StringBuilder();
            try (var stream = Files.list(dir)) {
                var textFiles = stream
                        .filter(p -> p.toString().endsWith(".txt"))
                        .sorted()
                        .collect(Collectors.toList());

                if (textFiles.isEmpty()) {
                    return "No text findings in directive folder: " + dir.toAbsolutePath();
                }

                for (Path file : textFiles) {
                    String content = Files.readString(file);
                    allText.append("=== ").append(file.getFileName()).append(" ===\n");
                    allText.append(content).append("\n\n");
                }
            }

            // Count images
            long imageCount;
            try (var stream = Files.list(dir)) {
                imageCount = stream
                        .filter(p -> {
                            String name = p.toString().toLowerCase();
                            return name.endsWith(".jpg") || name.endsWith(".jpeg")
                                    || name.endsWith(".png") || name.endsWith(".gif")
                                    || name.endsWith(".webp");
                        })
                        .count();
            }

            String rawText = allText.toString();
            if (rawText.length() > 50000) {
                rawText = rawText.substring(0, 50000) + "\n... (truncated)";
            }

            // Use AI to summarize if available
            if (chatClient != null) {
                String summaryPrompt = "Summarize the following research findings concisely. " +
                        "Highlight key facts, numbers, and actionable insights. " +
                        "Keep it under 500 words.\n\n" + rawText;
                String summary = chatClient.prompt()
                        .user(summaryPrompt)
                        .call()
                        .content();

                String result = "Summary for directive '" + directiveName + "':\n\n" + summary;
                if (imageCount > 0) {
                    result += "\n\n(" + imageCount + " images also saved in " + dir.toAbsolutePath() + ")";
                }

                // Save summary to file
                Path summaryFile = dir.resolve("_SUMMARY.txt");
                Files.writeString(summaryFile, result);
                log.info("[Summarization] Saved summary to {}", summaryFile);

                return result;
            } else {
                // No AI — return raw stats
                long wordCount = rawText.split("\\s+").length;
                return "No AI available for summarization. Raw stats for '" + directiveName + "':\n"
                        + "- Text files: " + allText.toString().split("===").length / 2 + "\n"
                        + "- Word count: ~" + wordCount + "\n"
                        + "- Images: " + imageCount + "\n"
                        + "- Folder: " + dir.toAbsolutePath();
            }
        } catch (Exception e) {
            log.error("[Summarization] Failed: {}", e.getMessage());
            return "Summarization failed: " + e.getMessage();
        }
    }

    @Tool(description = "List all directive folders and show a brief status for each " +
            "(number of findings, images, whether a summary exists).")
    public String directiveOverview() {
        notifier.notify("Getting directive overview");
        try {
            if (!Files.isDirectory(BASE_DIR)) {
                return "No mins_bot_data directory found.";
            }

            StringBuilder sb = new StringBuilder("Directive Overview:\n\n");
            int count = 0;
            try (var stream = Files.list(BASE_DIR)) {
                var dirs = stream
                        .filter(p -> Files.isDirectory(p) && p.getFileName().toString().startsWith("directive_"))
                        .sorted()
                        .collect(Collectors.toList());

                if (dirs.isEmpty()) return "No directive folders found.";

                for (Path dir : dirs) {
                    count++;
                    String name = dir.getFileName().toString().replace("directive_", "");
                    long txtCount = countFiles(dir, ".txt");
                    long imgCount = countImages(dir);
                    boolean hasSummary = Files.exists(dir.resolve("_SUMMARY.txt"));

                    sb.append(count).append(". ").append(name)
                            .append(" — ").append(txtCount).append(" text file(s)")
                            .append(", ").append(imgCount).append(" image(s)");
                    if (hasSummary) sb.append(" [SUMMARIZED]");
                    sb.append("\n");
                }
            }

            return sb.toString().trim();
        } catch (Exception e) {
            return "Failed to get overview: " + e.getMessage();
        }
    }

    private long countFiles(Path dir, String extension) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> p.toString().endsWith(extension)).count();
        }
    }

    private long countImages(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> {
                String n = p.toString().toLowerCase();
                return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                        || n.endsWith(".gif") || n.endsWith(".webp");
            }).count();
        }
    }

    private String sanitizeName(String name) {
        if (name == null || name.isBlank()) return "unnamed";
        String safe = name.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return safe.isEmpty() ? "unnamed" : (safe.length() > 60 ? safe.substring(0, 60) : safe);
    }
}
