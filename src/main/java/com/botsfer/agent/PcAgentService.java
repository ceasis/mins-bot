package com.botsfer.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interprets natural language commands and executes PC actions autonomously.
 * Runs long tasks in the background and reports results via callback.
 */
@Service
public class PcAgentService {

    private static final Logger log = LoggerFactory.getLogger(PcAgentService.class);

    private final FileCollectorService fileCollector;
    private final SystemControlService systemControl;
    private final BrowserControlService browserControl;

    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "pc-agent-worker");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, String> runningTasks = Collections.synchronizedMap(new LinkedHashMap<>());

    public PcAgentService(FileCollectorService fileCollector,
                          SystemControlService systemControl,
                          BrowserControlService browserControl) {
        this.fileCollector = fileCollector;
        this.systemControl = systemControl;
        this.browserControl = browserControl;
    }

    /**
     * Attempts to interpret a message as a PC command using regex matching.
     * Returns null if not recognized (falls through to normal chat).
     * This is the offline fallback when Spring AI ChatClient is not available.
     */
    public String tryExecute(String message, Consumer<String> asyncResultCallback) {
        if (message == null || message.isBlank()) return null;
        String lower = message.toLowerCase().trim();
        String original = message.trim();

        // ═══ FILE OPERATIONS (check first — most common user intent) ═══

        // ── Collect files by category (retrieve all photos, get my videos, etc.) ──
        String collectCategory = matchCollectCommand(lower);
        if (collectCategory != null) {
            String category = collectCategory;
            String taskId = "collect-" + category;
            runningTasks.put(taskId, "running");
            executor.submit(() -> {
                try {
                    String result = fileCollector.collectByCategory(category);
                    runningTasks.put(taskId, "done");
                    if (asyncResultCallback != null) asyncResultCallback.accept(result);
                } catch (Exception e) {
                    runningTasks.put(taskId, "error");
                    if (asyncResultCallback != null) asyncResultCallback.accept("Error: " + e.getMessage());
                }
            });
            return "On it! Scanning your PC for " + category + " files. I'll report back when done.";
        }

        // ── List collected files ──
        if (lower.contains("what") && (lower.contains("collected") || lower.contains("saved"))) {
            return listCollected();
        }

        // ── Open collected folder ──
        if (lower.startsWith("open collected") || lower.contains("open botsfer_data")) {
            return openFileOrFolder(Paths.get(System.getProperty("user.home"), "botsfer_data", "collected").toString());
        }

        // ── Search for files on disk ──
        String searchPattern = matchSearchCommand(lower);
        if (searchPattern != null) {
            String pattern = searchPattern;
            executor.submit(() -> {
                try {
                    String result = fileCollector.searchFiles(pattern, 50);
                    if (asyncResultCallback != null) asyncResultCallback.accept(result);
                } catch (Exception e) {
                    if (asyncResultCallback != null) asyncResultCallback.accept("Search error: " + e.getMessage());
                }
            });
            return "Searching for \"" + pattern + "\" across your PC...";
        }

        // ═══ WINDOW / APP CONTROL ═══

        // ── Close all windows ──
        if (matchesCloseAll(lower)) {
            return systemControl.closeAllWindows();
        }

        // ── Close all browsers ──
        if (lower.contains("close") && lower.contains("browser")) {
            return browserControl.closeAllBrowsers();
        }

        // ── Close specific app ──
        String closeTarget = matchCloseApp(lower);
        if (closeTarget != null) {
            return systemControl.closeApp(closeTarget);
        }

        // ── Minimize all ──
        if (matchesMinimizeAll(lower)) {
            return systemControl.minimizeAll();
        }

        // ── Lock screen ──
        if (lower.contains("lock") && (lower.contains("screen") || lower.contains("computer") || lower.contains("pc"))) {
            return systemControl.lockScreen();
        }

        // ── Take screenshot ──
        if (lower.contains("screenshot") || lower.contains("screen shot") || lower.contains("screen capture")) {
            return systemControl.takeScreenshot();
        }

        // ── List running apps ──
        if ((lower.contains("list") || lower.contains("show") || lower.contains("what")) &&
                (lower.contains("running") || lower.contains("open app") || lower.contains("open program") || lower.contains("processes"))) {
            return systemControl.listRunningApps();
        }

        // ── Task status ──
        if (lower.contains("task") && (lower.contains("status") || lower.contains("progress"))) {
            return getTaskStatus();
        }

        // ═══ BROWSER ═══

        // ── Search YouTube ──
        String ytQuery = matchYouTubeSearch(lower);
        if (ytQuery != null) {
            return browserControl.searchYouTube(ytQuery);
        }

        // ── Search Google ──
        String googleQuery = matchGoogleSearch(lower);
        if (googleQuery != null) {
            return browserControl.searchGoogle(googleQuery);
        }

        // ── Open URL / browse ──
        String url = matchUrl(lower, original);
        if (url != null) {
            return browserControl.openInBrowser("default", url);
        }

        // ── List browser tabs ──
        if (lower.contains("browser") && (lower.contains("tab") || lower.contains("window"))) {
            return browserControl.listBrowserTabs();
        }

        // ═══ OPEN / LAUNCH ═══

        // ── Open file or folder (path) ──
        String openPath = matchOpenPath(lower, original);
        if (openPath != null) {
            return openFileOrFolder(openPath);
        }

        // ── Open app (not a file path) ──
        String openApp = matchOpenApp(lower);
        if (openApp != null) {
            return systemControl.openApp(openApp);
        }

        // ═══ FILE MANIPULATION ═══

        // ── Copy file ──
        String[] copyArgs = matchCopyCommand(lower, original);
        if (copyArgs != null) {
            return copyFile(copyArgs[0], copyArgs[1]);
        }

        // ── Delete file ──
        String deletePath = matchDeleteCommand(lower, original);
        if (deletePath != null) {
            return deleteFile(deletePath);
        }

        // ═══ ADVANCED ═══

        // ── Run PowerShell ──
        String psCmd = matchPowerShell(lower, original);
        if (psCmd != null) {
            return systemControl.runPowerShell(psCmd);
        }

        // ── Run CMD ──
        String cmdCmd = matchCmd(lower, original);
        if (cmdCmd != null) {
            return systemControl.runCmd(cmdCmd);
        }

        // Not a command
        return null;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Command matchers
    // ══════════════════════════════════════════════════════════════════════

    private boolean matchesCloseAll(String lower) {
        return (lower.contains("close") || lower.contains("kill") || lower.contains("shut down") || lower.contains("terminate"))
                && (lower.contains("all window") || lower.contains("all app") || lower.contains("all program")
                || lower.contains("everything") || lower.contains("all other"));
    }

    private static final Pattern CLOSE_APP_PATTERN = Pattern.compile(
            "(?:close|kill|quit|exit|terminate|stop|end)\\s+(?:the\\s+)?(.+?)(?:\\s+app| application| program| window)?\\s*$",
            Pattern.CASE_INSENSITIVE
    );

    private String matchCloseApp(String lower) {
        // Don't match "close all" patterns
        if (lower.contains("all ")) return null;
        if (lower.contains("browser")) return null; // handled separately

        Matcher m = CLOSE_APP_PATTERN.matcher(lower);
        if (m.find()) {
            String target = m.group(1).trim();
            // Filter out non-app words
            if (target.equals("this") || target.equals("it") || target.equals("panel")) return null;
            return target;
        }
        return null;
    }

    private boolean matchesMinimizeAll(String lower) {
        return (lower.contains("minimize") && (lower.contains("all") || lower.contains("everything")))
                || lower.contains("show desktop")
                || lower.contains("hide all");
    }

    // Matches: "open chrome", "open notepad", "launch spotify", "start calculator"
    // But NOT paths like "open C:\..."
    private static final Pattern OPEN_APP_PATTERN = Pattern.compile(
            "(?:open|launch|start|run)\\s+(?:the\\s+)?([a-zA-Z][a-zA-Z0-9 ]{1,30})$",
            Pattern.CASE_INSENSITIVE
    );

    private String matchOpenApp(String lower) {
        // Don't match if it looks like a path or URL
        if (lower.contains(":\\") || lower.contains("://") || lower.contains("collected")) return null;

        Matcher m = OPEN_APP_PATTERN.matcher(lower);
        if (m.find()) {
            String app = m.group(1).trim();
            // Filter out file-related words that would conflict with other commands
            if (app.startsWith("file") || app.equals("url") || app.equals("folder")) return null;
            return app;
        }
        return null;
    }

    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?:open|go to|navigate to|browse|visit)\\s+(?:the\\s+)?(?:url\\s+|website\\s+|site\\s+)?[\"']?((?:https?://)?[a-zA-Z0-9][-a-zA-Z0-9.]+\\.[a-zA-Z]{2,}(?:/[^\\s\"']*)?)[\"']?",
            Pattern.CASE_INSENSITIVE
    );

    private String matchUrl(String lower, String original) {
        Matcher m = URL_PATTERN.matcher(original);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    private static final Pattern GOOGLE_PATTERN = Pattern.compile(
            "(?:google|search|search google|search for|google search)\\s+(?:for\\s+)?[\"']?(.+?)[\"']?\\s*$",
            Pattern.CASE_INSENSITIVE
    );

    private String matchGoogleSearch(String lower) {
        // Only trigger for explicit "google X" or "search google X"
        if (!lower.contains("google")) return null;
        if (lower.contains("youtube")) return null;
        Matcher m = GOOGLE_PATTERN.matcher(lower);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    private static final Pattern YT_PATTERN = Pattern.compile(
            "(?:youtube|search youtube|search youtube for|play on youtube)\\s+(?:for\\s+)?[\"']?(.+?)[\"']?\\s*$",
            Pattern.CASE_INSENSITIVE
    );

    private String matchYouTubeSearch(String lower) {
        if (!lower.contains("youtube")) return null;
        Matcher m = YT_PATTERN.matcher(lower);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    private String matchCollectCommand(String lower) {
        for (Map.Entry<String, Set<String>> entry : FileCollectorService.FILE_CATEGORIES.entrySet()) {
            String cat = entry.getKey();
            List<String> aliases = getCategoryAliases(cat);
            for (String alias : aliases) {
                if (lower.contains(alias)) {
                    if (lower.matches(".*(retrieve|collect|find|get|gather|scan|copy|grab|fetch|save|backup|all).*")) {
                        return cat;
                    }
                }
            }
        }
        return null;
    }

    private List<String> getCategoryAliases(String category) {
        return switch (category) {
            case "photos" -> List.of("photo", "image", "picture", "pic");
            case "videos" -> List.of("video", "movie", "clip");
            case "music" -> List.of("music", "song", "audio", "mp3");
            case "documents" -> List.of("document", "doc", "pdf", "spreadsheet");
            case "archives" -> List.of("archive", "zip", "compressed");
            default -> List.of(category);
        };
    }

    private static final Pattern SEARCH_PATTERN = Pattern.compile(
            "(?:search|find|look for|locate)\\s+(?:files?\\s+)?(?:named?|called|matching|like)?\\s*[\"']?([^\"']+)[\"']?",
            Pattern.CASE_INSENSITIVE
    );

    private String matchSearchCommand(String lower) {
        // Don't interfere with Google/YouTube or collect commands
        if (lower.contains("google") || lower.contains("youtube")) return null;
        // "find/search files named X" — disk search
        // But "find all photos" would already be handled by collectCommand above
        Matcher m = SEARCH_PATTERN.matcher(lower);
        if (m.find()) {
            String pat = m.group(1).trim();
            // Don't match file category words — those are collect commands
            for (String cat : new String[]{"photo", "image", "picture", "video", "movie",
                    "music", "song", "document", "archive"}) {
                if (pat.contains(cat)) return null;
            }
            if (!pat.contains("*") && !pat.contains("?")) {
                pat = "*" + pat + "*";
            }
            return pat;
        }
        return null;
    }

    private static final Pattern OPEN_PATH_PATTERN = Pattern.compile(
            "(?:open|show|launch|run|start)\\s+[\"']?([a-zA-Z]:\\\\[^\"']+|/[^\"']+|~[^\"']+)[\"']?",
            Pattern.CASE_INSENSITIVE
    );

    private String matchOpenPath(String lower, String original) {
        Matcher m = OPEN_PATH_PATTERN.matcher(original);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    private static final Pattern COPY_PATTERN = Pattern.compile(
            "(?:copy|move)\\s+[\"']?([^\"']+?)[\"']?\\s+(?:to|into)\\s+[\"']?([^\"']+)[\"']?",
            Pattern.CASE_INSENSITIVE
    );

    private String[] matchCopyCommand(String lower, String original) {
        Matcher m = COPY_PATTERN.matcher(original);
        if (m.find()) {
            return new String[]{m.group(1).trim(), m.group(2).trim()};
        }
        return null;
    }

    private static final Pattern DELETE_PATTERN = Pattern.compile(
            "(?:delete|remove|trash)\\s+(?:the\\s+)?(?:file\\s+)?[\"']?([a-zA-Z]:\\\\[^\"']+|/[^\"']+)[\"']?",
            Pattern.CASE_INSENSITIVE
    );

    private String matchDeleteCommand(String lower, String original) {
        Matcher m = DELETE_PATTERN.matcher(original);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    private static final Pattern PS_PATTERN = Pattern.compile(
            "(?:run|execute)\\s+(?:powershell|ps)\\s*:?\\s*(.+)",
            Pattern.CASE_INSENSITIVE
    );

    private String matchPowerShell(String lower, String original) {
        Matcher m = PS_PATTERN.matcher(original);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    private static final Pattern CMD_PATTERN = Pattern.compile(
            "(?:run|execute)\\s+(?:cmd|command|terminal)\\s*:?\\s*(.+)",
            Pattern.CASE_INSENSITIVE
    );

    private String matchCmd(String lower, String original) {
        Matcher m = CMD_PATTERN.matcher(original);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Action executors (file-level, shared with file collector)
    // ══════════════════════════════════════════════════════════════════════

    private String openFileOrFolder(String pathStr) {
        try {
            Path path = Paths.get(pathStr).toAbsolutePath();
            if (!Files.exists(path)) {
                return "Path not found: " + path;
            }
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(path.toFile());
                return "Opened: " + path;
            }
            new ProcessBuilder("explorer.exe", path.toString()).start();
            return "Opened: " + path;
        } catch (Exception e) {
            return "Failed to open: " + e.getMessage();
        }
    }

    private String copyFile(String source, String dest) {
        try {
            Path src = Paths.get(source).toAbsolutePath();
            Path dst = Paths.get(dest).toAbsolutePath();
            if (!Files.exists(src)) {
                return "Source not found: " + src;
            }
            if (Files.isDirectory(dst)) {
                dst = dst.resolve(src.getFileName());
            }
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            return "Copied " + src.getFileName() + " to " + dst;
        } catch (IOException e) {
            return "Copy failed: " + e.getMessage();
        }
    }

    private String deleteFile(String pathStr) {
        try {
            Path path = Paths.get(pathStr).toAbsolutePath();
            if (!Files.exists(path)) {
                return "File not found: " + path;
            }
            if (Files.isDirectory(path)) {
                return "I can only delete individual files, not directories. Path: " + path;
            }
            long size = Files.size(path);
            Files.delete(path);
            return "Deleted: " + path + " (" + formatSize(size) + ")";
        } catch (IOException e) {
            return "Delete failed: " + e.getMessage();
        }
    }

    private String listCollected() {
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

    private String getTaskStatus() {
        if (runningTasks.isEmpty()) {
            return "No tasks running.";
        }
        StringBuilder sb = new StringBuilder("Tasks:\n");
        runningTasks.forEach((id, status) ->
                sb.append("  ").append(id).append(": ").append(status).append("\n"));
        return sb.toString();
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        double gb = mb / 1024.0;
        return String.format("%.1f GB", gb);
    }
}
