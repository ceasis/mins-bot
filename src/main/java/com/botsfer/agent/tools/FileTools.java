package com.botsfer.agent.tools;

import com.botsfer.agent.FileCollectorService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.*;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

@Component
public class FileTools {

    private final FileCollectorService fileCollector;
    private final ToolExecutionNotifier notifier;

    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "file-tools-worker");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, String> runningTasks = Collections.synchronizedMap(new LinkedHashMap<>());

    /** Set by ChatService before each ChatClient call. */
    private volatile Consumer<String> asyncCallback;

    public FileTools(FileCollectorService fileCollector, ToolExecutionNotifier notifier) {
        this.fileCollector = fileCollector;
        this.notifier = notifier;
    }

    public void setAsyncCallback(Consumer<String> callback) {
        this.asyncCallback = callback;
    }

    public Map<String, String> getRunningTasks() {
        return runningTasks;
    }

    @Tool(description = "Scan the entire PC and collect all files of a given category "
            + "(photos, videos, music, documents, archives) into a central folder. "
            + "This is a long-running background task.", returnDirect = true)
    public String collectFiles(
            @ToolParam(description = "File category: photos, videos, music, documents, or archives") String category) {
        notifier.notify("Collecting " + category + " files...");
        String taskId = "collect-" + category;
        runningTasks.put(taskId, "running");
        Consumer<String> cb = asyncCallback;
        executor.submit(() -> {
            try {
                String result = fileCollector.collectByCategory(category);
                runningTasks.put(taskId, "done");
                if (cb != null) cb.accept(result);
            } catch (Exception e) {
                runningTasks.put(taskId, "error");
                if (cb != null) cb.accept("Error: " + e.getMessage());
            }
        });
        return "On it! Scanning your PC for " + category + " files. I'll report back when done.";
    }

    @Tool(description = "Search for files by name pattern across the PC. "
            + "This is a long-running background task.", returnDirect = true)
    public String searchFiles(
            @ToolParam(description = "File name pattern to search for, e.g. 'report' or '*.pdf'") String pattern) {
        notifier.notify("Searching files: " + pattern);
        String pat = pattern;
        if (!pat.contains("*") && !pat.contains("?")) {
            pat = "*" + pat + "*";
        }
        String finalPat = pat;
        Consumer<String> cb = asyncCallback;
        executor.submit(() -> {
            try {
                String result = fileCollector.searchFiles(finalPat, 50);
                if (cb != null) cb.accept(result);
            } catch (Exception e) {
                if (cb != null) cb.accept("Search error: " + e.getMessage());
            }
        });
        return "Searching for \"" + pat + "\" across your PC...";
    }

    @Tool(description = "Show what files have been collected so far, grouped by category with file counts and sizes")
    public String listCollected() {
        notifier.notify("Checking collected files...");
        Path base = Paths.get(System.getProperty("user.home"), "botsfer_data", "collected");
        if (!Files.isDirectory(base)) {
            return "No files collected yet. Ask me to collect photos, videos, documents, etc.";
        }
        StringBuilder sb = new StringBuilder("Collected files:\n");
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(base)) {
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir)) continue;
                long count = 0;
                long totalSize = 0;
                try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
                    for (Path f : files) {
                        if (Files.isRegularFile(f)) {
                            count++;
                            totalSize += Files.size(f);
                        }
                    }
                }
                sb.append("  ").append(dir.getFileName()).append(": ")
                        .append(count).append(" files (").append(formatSize(totalSize)).append(")\n");
            }
        } catch (IOException e) {
            return "Error reading collected files: " + e.getMessage();
        }
        return sb.toString();
    }

    @Tool(description = "Open the folder containing all collected files in file explorer")
    public String openCollectedFolder() {
        notifier.notify("Opening collected folder...");
        try {
            Path path = Paths.get(System.getProperty("user.home"), "botsfer_data", "collected");
            Files.createDirectories(path);
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(path.toFile());
                return "Opened: " + path;
            }
            new ProcessBuilder("explorer.exe", path.toString()).start();
            return "Opened: " + path;
        } catch (Exception e) {
            return "Failed to open collected folder: " + e.getMessage();
        }
    }

    static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        double gb = mb / 1024.0;
        return String.format("%.1f GB", gb);
    }
}
