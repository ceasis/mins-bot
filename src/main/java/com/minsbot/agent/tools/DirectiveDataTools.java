package com.minsbot.agent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Tools for saving research data (text findings and screenshots) into
 * per-directive folders under ~/mins_bot_data/directive_{name}/.
 */
@Component
public class DirectiveDataTools {

    // All directive scratchpads live under mins_workfolder so the user's
    // mins_bot_data root stays clean (config + memory only). The 30-day
    // prune in DeliverableExecutor sweeps stale dirs here too.
    private static final Path BASE_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "mins_workfolder");
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final ToolExecutionNotifier notifier;

    public DirectiveDataTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @Tool(description = "Save a text finding to a directive's data folder. " +
            "Creates ~/mins_bot_data/directive_{name}/ if it doesn't exist. " +
            "Use this to store research results, gathered information, or progress notes for a specific directive.")
    public String saveDirectiveFinding(
            @ToolParam(description = "Short name of the directive (e.g. 'search-condo-new-york'). Used as folder name.") String directiveName,
            @ToolParam(description = "The text content to save (research findings, data, notes, etc.)") String content) {
        notifier.notify("Saving finding for: " + directiveName);
        try {
            Path dir = getDirectiveDir(directiveName);
            Files.createDirectories(dir);
            String filename = LocalDateTime.now().format(TS_FMT) + "_finding.txt";
            Path file = dir.resolve(filename);
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return "Finding saved: " + file.toAbsolutePath();
        } catch (Exception e) {
            return "Failed to save finding: " + e.getMessage();
        }
    }

    @Tool(description = "Take a screenshot and save it to a directive's data folder. " +
            "Use this to capture the current screen state as evidence or reference for a directive's research.")
    public String saveDirectiveScreenshot(
            @ToolParam(description = "Short name of the directive (e.g. 'search-condo-new-york'). Used as folder name.") String directiveName) {
        notifier.notify("Capturing screenshot for: " + directiveName);
        try {
            Path dir = getDirectiveDir(directiveName);
            Files.createDirectories(dir);

            Rectangle screenRect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            BufferedImage image = new Robot().createScreenCapture(screenRect);
            com.minsbot.agent.SystemControlService.drawCursorOnImage(image, 0, 0);

            String filename = LocalDateTime.now().format(TS_FMT) + "_screenshot.png";
            Path file = dir.resolve(filename);
            ImageIO.write(image, "png", file.toFile());
            return "Screenshot saved: " + file.toAbsolutePath();
        } catch (Exception e) {
            return "Failed to save screenshot: " + e.getMessage();
        }
    }

    @Tool(description = "List all files gathered for a directive (text findings and screenshots).")
    public String listDirectiveData(
            @ToolParam(description = "Short name of the directive") String directiveName) {
        notifier.notify("Listing data for: " + directiveName);
        try {
            Path dir = getDirectiveDir(directiveName);
            if (!Files.isDirectory(dir)) {
                return "No data folder found for directive: " + directiveName;
            }
            List<String> files = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                stream.forEach(p -> files.add(p.getFileName().toString()));
            }
            if (files.isEmpty()) return "Folder exists but is empty: " + dir;
            files.sort(String::compareTo);
            StringBuilder sb = new StringBuilder("Data for \"" + directiveName + "\" (" + dir + "):\n");
            for (String f : files) {
                sb.append("  - ").append(f).append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "Failed to list data: " + e.getMessage();
        }
    }

    @Tool(description = "Read a text finding file from a directive's data folder.")
    public String readDirectiveFinding(
            @ToolParam(description = "Short name of the directive") String directiveName,
            @ToolParam(description = "Filename to read (e.g. '2026-02-12_14-30-15_finding.txt')") String filename) {
        notifier.notify("Reading finding: " + filename);
        try {
            Path file = getDirectiveDir(directiveName).resolve(sanitizeFilename(filename));
            if (!Files.exists(file)) {
                return "File not found: " + file;
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.length() > 5000) {
                return content.substring(0, 5000) + "\n... (truncated, " + content.length() + " chars total)";
            }
            return content;
        } catch (Exception e) {
            return "Failed to read finding: " + e.getMessage();
        }
    }

    @Tool(description = "List all directive data folders that exist under mins_bot_data/.")
    public String listAllDirectiveFolders() {
        notifier.notify("Listing directive folders...");
        try {
            List<String> folders = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(BASE_DIR, "directive_*")) {
                stream.forEach(p -> {
                    if (Files.isDirectory(p)) folders.add(p.getFileName().toString());
                });
            }
            if (folders.isEmpty()) return "No directive data folders exist yet.";
            folders.sort(String::compareTo);
            StringBuilder sb = new StringBuilder("Directive data folders:\n");
            for (String f : folders) {
                sb.append("  - ").append(f).append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "Failed to list folders: " + e.getMessage();
        }
    }

    /** Build the directive folder path: ~/mins_bot_data/directive_{sanitized_name}/ */
    private Path getDirectiveDir(String directiveName) {
        return BASE_DIR.resolve("directive_" + sanitizeName(directiveName));
    }

    /** Sanitize a directive name into a safe folder name. */
    private String sanitizeName(String name) {
        if (name == null || name.isBlank()) return "unnamed";
        String safe = name.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")  // non-alphanumeric → dash
                .replaceAll("^-+|-+$", "");     // strip leading/trailing dashes
        if (safe.isEmpty()) return "unnamed";
        return safe.length() > 60 ? safe.substring(0, 60) : safe;
    }

    /** Sanitize a filename to prevent path traversal. */
    private String sanitizeFilename(String filename) {
        if (filename == null) return "unknown";
        return filename.replaceAll("[/\\\\]", "").replaceAll("\\.\\.", "");
    }
}
