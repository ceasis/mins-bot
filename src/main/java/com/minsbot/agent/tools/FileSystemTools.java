package com.minsbot.agent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@Component
public class FileSystemTools {

    private final ToolExecutionNotifier notifier;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    public FileSystemTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    // ─── Browse & inspect ────────────────────────────────────────────────────

    @Tool(description = "List the contents of a directory programmatically and return the names as text — "
            + "does NOT open File Explorer. Returns name, type (file/dir), size, and last modified date. "
            + "USE THIS when the user asks 'what's in my Downloads folder', 'list files in X', "
            + "'show me contents of Y', 'what files are in Z'. "
            + "NEVER call openPath / openApp('explorer') for listing/counting questions — this tool reads the directory directly.")
    public String listDirectory(
            @ToolParam(description = "Full path to the directory to list") String path) {
        notifier.notify("Listing " + path + "...");
        try {
            Path dir = Paths.get(path).toAbsolutePath();
            if (!Files.exists(dir)) return "Path not found: " + dir;
            if (!Files.isDirectory(dir)) return "Not a directory: " + dir;

            StringBuilder sb = new StringBuilder();
            sb.append("Contents of ").append(dir).append(":\n");
            long fileCount = 0, dirCount = 0;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path entry : stream) {
                    boolean isDir = Files.isDirectory(entry);
                    if (isDir) dirCount++; else fileCount++;
                    long size = isDir ? 0 : Files.size(entry);
                    Instant mod = Files.getLastModifiedTime(entry).toInstant();
                    sb.append(String.format("  %-4s  %10s  %s  %s\n",
                            isDir ? "DIR" : "FILE",
                            isDir ? "" : formatSize(size),
                            FMT.format(mod),
                            entry.getFileName()));
                }
            }
            sb.append("Total: ").append(fileCount).append(" file(s), ").append(dirCount).append(" folder(s)");
            return sb.toString();
        } catch (IOException e) {
            return "Failed to list directory: " + e.getMessage();
        }
    }

    @Tool(description = "Count how many files and directories are in a given directory (non-recursive, top-level only). "
            + "USE THIS when the user asks 'how many files in my Downloads', 'how many items in X folder', "
            + "'count files in Y', 'how many things are in Z'. Returns a text count — does NOT open File Explorer. "
            + "NEVER open Explorer for counting questions.")
    public String countDirectoryContents(
            @ToolParam(description = "Full path to the directory to inspect") String path) {
        notifier.notify("Counting contents of " + path + "...");
        try {
            Path dir = Paths.get(path).toAbsolutePath();
            if (!Files.exists(dir)) return "Path not found: " + dir;
            if (!Files.isDirectory(dir)) return "Not a directory: " + dir;
            long fileCount = 0, dirCount = 0;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path entry : stream) {
                    if (Files.isDirectory(entry)) dirCount++; else fileCount++;
                }
            }
            return dir + " contains " + fileCount + " file(s) and " + dirCount + " folder(s).";
        } catch (IOException e) {
            return "Failed to read directory: " + e.getMessage();
        }
    }

    @Tool(description = "Get detailed info about a file or directory: size, dates, attributes, and for directories the total size recursively")
    public String getFileInfo(
            @ToolParam(description = "Full path to the file or directory") String path) {
        notifier.notify("Getting info for " + path + "...");
        try {
            Path p = Paths.get(path).toAbsolutePath();
            if (!Files.exists(p)) return "Path not found: " + p;
            BasicFileAttributes attr = Files.readAttributes(p, BasicFileAttributes.class);
            StringBuilder sb = new StringBuilder();
            sb.append("Path: ").append(p).append("\n");
            sb.append("Type: ").append(attr.isDirectory() ? "Directory" : attr.isSymbolicLink() ? "Symlink" : "File").append("\n");
            sb.append("Created: ").append(FMT.format(attr.creationTime().toInstant())).append("\n");
            sb.append("Modified: ").append(FMT.format(attr.lastModifiedTime().toInstant())).append("\n");
            sb.append("Hidden: ").append(Files.isHidden(p)).append("\n");
            sb.append("Readable: ").append(Files.isReadable(p)).append("\n");
            sb.append("Writable: ").append(Files.isWritable(p)).append("\n");
            if (attr.isDirectory()) {
                AtomicLong totalSize = new AtomicLong();
                AtomicLong totalFiles = new AtomicLong();
                AtomicLong totalDirs = new AtomicLong();
                Files.walkFileTree(p, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                        totalSize.addAndGet(a.size());
                        totalFiles.incrementAndGet();
                        return FileVisitResult.CONTINUE;
                    }
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                        totalDirs.incrementAndGet();
                        return FileVisitResult.CONTINUE;
                    }
                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
                sb.append("Total size: ").append(formatSize(totalSize.get())).append("\n");
                sb.append("Contains: ").append(totalFiles.get()).append(" files, ").append(totalDirs.get() - 1).append(" subdirectories");
            } else {
                sb.append("Size: ").append(formatSize(attr.size())).append(" (").append(attr.size()).append(" bytes)");
            }
            return sb.toString();
        } catch (IOException e) {
            return "Failed to get info: " + e.getMessage();
        }
    }

    @Tool(description = "Check whether a file or directory exists at the given path")
    public String pathExists(
            @ToolParam(description = "Full path to check") String path) {
        Path p = Paths.get(path).toAbsolutePath();
        if (!Files.exists(p)) return "Does not exist: " + p;
        return (Files.isDirectory(p) ? "Directory" : "File") + " exists: " + p;
    }

    @Tool(description = "List all available disk drives with total space, free space, and used percentage")
    public String listDrives() {
        notifier.notify("Listing drives...");
        StringBuilder sb = new StringBuilder("Drives:\n");
        for (Path root : FileSystems.getDefault().getRootDirectories()) {
            try {
                java.nio.file.FileStore store = Files.getFileStore(root);
                long total = store.getTotalSpace();
                long free = store.getUsableSpace();
                long used = total - free;
                int pct = total > 0 ? (int) (used * 100 / total) : 0;
                sb.append(String.format("  %s  %s total, %s free, %s used (%d%%)\n",
                        root, formatSize(total), formatSize(free), formatSize(used), pct));
            } catch (IOException e) {
                sb.append("  ").append(root).append("  (not accessible)\n");
            }
        }
        return sb.toString();
    }

    // ─── Create ──────────────────────────────────────────────────────────────

    @Tool(description = "Create a new directory (and any missing parent directories)")
    public String createDirectory(
            @ToolParam(description = "Full path for the new directory") String path) {
        notifier.notify("Creating directory " + path + "...");
        try {
            Path p = Paths.get(path).toAbsolutePath();
            if (Files.exists(p)) return "Already exists: " + p;
            Files.createDirectories(p);
            return "Created directory: " + p;
        } catch (IOException e) {
            return "Failed to create directory: " + e.getMessage();
        }
    }

    @Tool(description = "Create or overwrite a text file with the given content")
    public String writeTextFile(
            @ToolParam(description = "Full path for the file") String path,
            @ToolParam(description = "Text content to write") String content) {
        notifier.notify("Writing file " + path + "...");
        try {
            Path p = Paths.get(path).toAbsolutePath();
            Files.createDirectories(p.getParent());
            Files.writeString(p, content, StandardCharsets.UTF_8);
            return "Written " + content.length() + " chars to " + p;
        } catch (IOException e) {
            return "Failed to write file: " + e.getMessage();
        }
    }

    // ─── Document discovery ─────────────────────────────────────────────────

    @Tool(description = "Find the LATEST document file matching name keywords across common locations "
            + "(Desktop, Downloads, Documents by default — searched recursively). "
            + "Use when the user asks 'find my latest CV', 'find my most recent invoice', "
            + "'where's my latest report', 'open my newest contract'. "
            + "Filters by document extensions (.pdf .doc .docx .odt .rtf .txt .pages .md), "
            + "matches keywords case-insensitively in the filename, sorts by last-modified date "
            + "(newest first). Returns the top 5 matches and (if openTopMatch=true) opens the latest "
            + "in the user's default app. ENTIRELY background — does NOT open File Explorer.")
    public String findLatestDocument(
            @ToolParam(description = "Keywords to match in filename, comma-separated. "
                    + "Examples: 'cv,resume,curriculum', 'invoice,bill,receipt', 'contract,agreement,nda', 'report'") String keywords,
            @ToolParam(description = "Folders to search, semicolon-separated. Empty string = default "
                    + "Desktop + Downloads + Documents under user's home.") String locations,
            @ToolParam(description = "true to OPEN the latest match in the default app, false to just list matches without opening") boolean openTopMatch) {
        notifier.notify("Finding latest document matching: " + keywords);
        try {
            // 1. Parse keywords (lowercase, trim, drop empty)
            List<String> kws = new ArrayList<>();
            if (keywords != null) {
                for (String k : keywords.split(",")) {
                    String s = k.trim().toLowerCase();
                    if (!s.isEmpty()) kws.add(s);
                }
            }
            if (kws.isEmpty()) return "No keywords provided.";

            // 2. Resolve locations
            List<Path> roots = new ArrayList<>();
            if (locations == null || locations.isBlank()) {
                Path home = Paths.get(System.getProperty("user.home"));
                for (String name : new String[]{"Desktop", "Downloads", "Documents"}) {
                    Path p = home.resolve(name);
                    if (Files.isDirectory(p)) roots.add(p);
                }
            } else {
                for (String loc : locations.split(";")) {
                    String s = loc.trim();
                    if (s.isEmpty()) continue;
                    Path p = Paths.get(s).toAbsolutePath();
                    if (Files.isDirectory(p)) roots.add(p);
                }
            }
            if (roots.isEmpty()) return "No valid folders to search.";

            // 3. Recursively find matches (cap at 1000 candidates to bound work)
            Set<String> docExts = Set.of(".pdf", ".doc", ".docx", ".odt", ".rtf", ".txt", ".pages", ".md");
            Set<String> skipDirs = Set.of("node_modules", ".git", "$recycle.bin",
                    "system volume information", ".gradle", "target", "build", ".idea", ".vscode");

            List<Path> matches = new ArrayList<>();
            int[] scanned = {0};
            for (Path root : roots) {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (skipDirs.contains(dir.getFileName().toString().toLowerCase())) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (scanned[0]++ > 50_000) return FileVisitResult.TERMINATE;
                        String name = file.getFileName().toString().toLowerCase();
                        int dot = name.lastIndexOf('.');
                        if (dot < 0) return FileVisitResult.CONTINUE;
                        String ext = name.substring(dot);
                        if (!docExts.contains(ext)) return FileVisitResult.CONTINUE;
                        String stem = name.substring(0, dot);
                        for (String kw : kws) {
                            if (stem.contains(kw)) {
                                matches.add(file);
                                break;
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            }

            if (matches.isEmpty()) {
                return "No documents found matching " + kws + " in " + roots
                        + ". (Searched " + scanned[0] + " files.)";
            }

            // 4. Sort by lastModifiedTime descending
            matches.sort((a, b) -> {
                try {
                    return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                } catch (IOException e) {
                    return 0;
                }
            });

            // 5. Build report
            StringBuilder sb = new StringBuilder();
            sb.append("Found ").append(matches.size()).append(" matching document(s) for ")
                    .append(kws).append(". Top ").append(Math.min(5, matches.size())).append(":\n");
            int n = Math.min(5, matches.size());
            for (int i = 0; i < n; i++) {
                Path p = matches.get(i);
                Instant mod = Files.getLastModifiedTime(p).toInstant();
                long size = Files.size(p);
                sb.append(String.format("  %d. %s  (%s, %s)%n",
                        i + 1, p, FMT.format(mod), formatSize(size)));
            }

            // 6. Optionally open the top match
            if (openTopMatch) {
                Path top = matches.get(0);
                try {
                    if (Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().open(top.toFile());
                        sb.append("\nOpened the latest: ").append(top);
                    } else {
                        new ProcessBuilder("explorer.exe", top.toString())
                                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                .redirectError(ProcessBuilder.Redirect.DISCARD)
                                .start();
                        sb.append("\nOpened the latest: ").append(top);
                    }
                } catch (Exception e) {
                    sb.append("\nFound the latest but couldn't open it: ").append(e.getMessage());
                }
            }
            return sb.toString();
        } catch (IOException e) {
            return "Search failed: " + e.getMessage();
        }
    }

    // ─── Read ────────────────────────────────────────────────────────────────

    @Tool(description = "Read the text content of a file (first 10000 characters). For viewing text files, logs, configs, etc.")
    public String readTextFile(
            @ToolParam(description = "Full path to the text file") String path) {
        notifier.notify("Reading " + path + "...");
        try {
            Path p = Paths.get(path).toAbsolutePath();
            if (!Files.exists(p)) return "File not found: " + p;
            if (Files.isDirectory(p)) return "Cannot read a directory. Use listDirectory instead.";
            long size = Files.size(p);
            if (size > 500_000) return "File too large to read as text (" + formatSize(size) + "). Max 500 KB.";
            String content = Files.readString(p, StandardCharsets.UTF_8);
            if (content.length() > 10_000) {
                return content.substring(0, 10_000) + "\n...(truncated, " + content.length() + " chars total)";
            }
            return content;
        } catch (IOException e) {
            return "Failed to read file: " + e.getMessage();
        }
    }

    // ─── Copy / Move / Rename ────────────────────────────────────────────────

    @Tool(description = "Copy a file from a source path to a destination path")
    public String copyFile(
            @ToolParam(description = "Source file path") String source,
            @ToolParam(description = "Destination file or folder path") String destination) {
        notifier.notify("Copying file to " + destination + "...");
        try {
            Path src = Paths.get(source).toAbsolutePath();
            Path dst = Paths.get(destination).toAbsolutePath();
            if (!Files.exists(src)) return "Source not found: " + src;
            if (Files.isDirectory(dst)) dst = dst.resolve(src.getFileName());
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            return "Copied " + src.getFileName() + " to " + dst;
        } catch (IOException e) {
            return "Copy failed: " + e.getMessage();
        }
    }

    @com.minsbot.approval.RequiresApproval(
            value = com.minsbot.approval.RiskLevel.DESTRUCTIVE,
            summary = "Move {source} → {destination}")
    @Tool(description = "Move a file or directory to a different location")
    public String movePath(
            @ToolParam(description = "Current full path of the file or directory") String source,
            @ToolParam(description = "Destination path or directory to move into") String destination) {
        notifier.notify("Moving " + source + " to " + destination + "...");
        try {
            Path src = Paths.get(source).toAbsolutePath();
            if (!Files.exists(src)) return "Source not found: " + src;
            Path dst = Paths.get(destination).toAbsolutePath();
            if (Files.isDirectory(dst)) dst = dst.resolve(src.getFileName());
            Files.createDirectories(dst.getParent());
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
            return "Moved: " + src + " → " + dst;
        } catch (IOException e) {
            return "Move failed: " + e.getMessage();
        }
    }

    @com.minsbot.approval.RequiresApproval(
            value = com.minsbot.approval.RiskLevel.DESTRUCTIVE,
            summary = "Move all {pattern} files from {sourceDir} to {destinationDir}")
    @Tool(description = "Move all files matching a glob pattern (e.g. *.txt, *.pdf, report*) from a source directory into a destination directory")
    public String moveByPattern(
            @ToolParam(description = "Source directory containing the files") String sourceDir,
            @ToolParam(description = "Glob pattern to match filenames, e.g. '*.txt', '*.pdf', 'report*'") String pattern,
            @ToolParam(description = "Destination directory to move matching files into") String destinationDir) {
        notifier.notify("Moving " + pattern + " files to " + destinationDir + "...");
        try {
            Path src = Paths.get(sourceDir).toAbsolutePath();
            Path dst = Paths.get(destinationDir).toAbsolutePath();
            if (!Files.exists(src) || !Files.isDirectory(src)) return "Source directory not found: " + src;
            Files.createDirectories(dst);

            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            int moved = 0, failed = 0;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(src)) {
                for (Path entry : stream) {
                    if (!Files.isDirectory(entry) && matcher.matches(entry.getFileName())) {
                        try {
                            Path target = dst.resolve(entry.getFileName());
                            Files.move(entry, target, StandardCopyOption.REPLACE_EXISTING);
                            moved++;
                        } catch (IOException e) {
                            failed++;
                        }
                    }
                }
            }
            if (moved == 0 && failed == 0) return "No files matching '" + pattern + "' found in " + src;
            String result = "Moved " + moved + " file(s) matching '" + pattern + "' to " + dst;
            if (failed > 0) result += " (" + failed + " failed)";
            return result;
        } catch (IOException e) {
            return "Move by pattern failed: " + e.getMessage();
        }
    }

    @Tool(description = "Copy all files matching a glob pattern (e.g. *.txt, *.pdf) from a source directory into a destination directory")
    public String copyByPattern(
            @ToolParam(description = "Source directory containing the files") String sourceDir,
            @ToolParam(description = "Glob pattern to match filenames, e.g. '*.txt', '*.pdf'") String pattern,
            @ToolParam(description = "Destination directory to copy matching files into") String destinationDir) {
        notifier.notify("Copying " + pattern + " files to " + destinationDir + "...");
        try {
            Path src = Paths.get(sourceDir).toAbsolutePath();
            Path dst = Paths.get(destinationDir).toAbsolutePath();
            if (!Files.exists(src) || !Files.isDirectory(src)) return "Source directory not found: " + src;
            Files.createDirectories(dst);

            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            int copied = 0, failed = 0;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(src)) {
                for (Path entry : stream) {
                    if (!Files.isDirectory(entry) && matcher.matches(entry.getFileName())) {
                        try {
                            Path target = dst.resolve(entry.getFileName());
                            Files.copy(entry, target, StandardCopyOption.REPLACE_EXISTING);
                            copied++;
                        } catch (IOException e) {
                            failed++;
                        }
                    }
                }
            }
            if (copied == 0 && failed == 0) return "No files matching '" + pattern + "' found in " + src;
            String result = "Copied " + copied + " file(s) matching '" + pattern + "' to " + dst;
            if (failed > 0) result += " (" + failed + " failed)";
            return result;
        } catch (IOException e) {
            return "Copy by pattern failed: " + e.getMessage();
        }
    }

    @Tool(description = "Rename a file or folder. Give the full path and the new name (just the name, not the full path).")
    public String rename(
            @ToolParam(description = "Full path of the file or folder to rename") String path,
            @ToolParam(description = "New name (just the filename or folder name, not a full path)") String newName) {
        notifier.notify("Renaming " + path + " to " + newName + "...");
        try {
            Path src = Paths.get(path).toAbsolutePath();
            if (!Files.exists(src)) return "Not found: " + src;
            Path dst = src.getParent().resolve(newName);
            if (Files.exists(dst)) return "A file/folder named '" + newName + "' already exists at " + dst;
            Files.move(src, dst);
            return "Renamed: " + src.getFileName() + " → " + newName;
        } catch (IOException e) {
            return "Rename failed: " + e.getMessage();
        }
    }

    // ─── Delete ──────────────────────────────────────────────────────────────

    @com.minsbot.approval.RequiresApproval(
            value = com.minsbot.approval.RiskLevel.DESTRUCTIVE,
            summary = "Delete the file at {path}")
    @Tool(description = "Delete a single file by its full path")
    public String deleteFile(
            @ToolParam(description = "Full path to the file to delete") String path) {
        notifier.notify("Deleting file " + path + "...");
        try {
            Path p = Paths.get(path).toAbsolutePath();
            if (!Files.exists(p)) return "File not found: " + p;
            if (Files.isDirectory(p)) return "Use deleteDirectory to remove directories. Path: " + p;
            long size = Files.size(p);
            Files.delete(p);
            return "Deleted: " + p + " (" + formatSize(size) + ")";
        } catch (IOException e) {
            return "Delete failed: " + e.getMessage();
        }
    }

    @com.minsbot.approval.RequiresApproval(
            value = com.minsbot.approval.RiskLevel.DESTRUCTIVE,
            summary = "Recursively delete the directory {path}")
    @Tool(description = "Delete a directory and all its contents recursively. Use with caution.")
    public String deleteDirectory(
            @ToolParam(description = "Full path to the directory to delete") String path) {
        notifier.notify("Deleting directory " + path + "...");
        try {
            Path dir = Paths.get(path).toAbsolutePath();
            if (!Files.exists(dir)) return "Directory not found: " + dir;
            if (!Files.isDirectory(dir)) return "Not a directory: " + dir;

            // Safety: refuse to delete drive roots or critical system paths
            if (dir.getParent() == null || dir.equals(dir.getRoot())) {
                return "Refused: cannot delete a drive root.";
            }
            String lower = dir.toString().toLowerCase();
            if (lower.contains("windows") || lower.contains("system32") || lower.contains("program files")
                    || lower.contains("$recycle.bin") || lower.contains("programdata")) {
                return "Refused: cannot delete system directory: " + dir;
            }

            AtomicLong count = new AtomicLong();
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.delete(p); count.incrementAndGet(); } catch (IOException ignored) {}
                });
            }
            return "Deleted directory " + dir + " (" + count.get() + " items removed)";
        } catch (IOException e) {
            return "Delete failed: " + e.getMessage();
        }
    }

    // ─── Open ────────────────────────────────────────────────────────────────

    @Tool(description = "Open a SPECIFIC file in its default application (Word, Acrobat, Excel, image viewer, etc.). "
            + "Use when the user wants to OPEN/VIEW a specific file by full path — e.g. a .docx, .pdf, .xlsx. "
            + "If a folder path is passed, this tool will SILENTLY switch to listDirectory and return the contents as text "
            + "(backend-first behavior — no Explorer window). "
            + "To explicitly open a folder in File Explorer, use openFolderInExplorer instead.")
    public String openPath(
            @ToolParam(description = "Full path to a specific file (NOT a URL — use openUrl for web pages)") String path) {
        try {
            if (path == null || path.isBlank()) return "Error: path is required.";
            // Detect URLs and delegate to the browser — a common LLM mistake is passing
            // 'https://arxiv.org/' here, which crashes Paths.get with 'Illegal char <:>'.
            String trimmed = path.trim();
            if (trimmed.matches("(?i)^(https?|ftp)://.*") || trimmed.startsWith("www.")) {
                try {
                    String url = trimmed.startsWith("www.") ? "https://" + trimmed : trimmed;
                    notifier.notify("Opening URL in browser: " + url + "...");
                    if (Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().browse(java.net.URI.create(url));
                    } else {
                        new ProcessBuilder("cmd", "/c", "start", "", url).start();
                    }
                    return "Opened URL in default browser: " + url
                            + "\n(NOTE: openPath is for local files; use openUrl for web pages next time.)";
                } catch (Exception urlErr) {
                    return "Failed to open URL: " + urlErr.getMessage();
                }
            }
            Path p = Paths.get(path).toAbsolutePath();
            if (!Files.exists(p)) return "Path not found: " + p;

            // BACKEND-FIRST: if it's a folder, silently return the listing instead of opening Explorer.
            // The user can still ask explicitly via openFolderInExplorer if they want a visible window.
            if (Files.isDirectory(p)) {
                notifier.notify("Listing " + p + " (backend, no Explorer)...");
                return listDirectory(p.toString());
            }

            notifier.notify("Opening " + p + "...");
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(p.toFile());
                return "Opened: " + p;
            }
            new ProcessBuilder("explorer.exe", p.toString()).start();
            return "Opened: " + p;
        } catch (Exception e) {
            return "Failed to open: " + e.getMessage();
        }
    }

    @Tool(description = "Open a folder in File Explorer — a visible window showing the folder contents. "
            + "Use ONLY when the user explicitly asks to 'open the folder in Explorer', 'show me the folder', "
            + "'reveal in Explorer', or similar visual-browse requests. "
            + "For answering questions about files (count, list, search), prefer the backend tools: "
            + "listDirectory, countDirectoryContents, searchInDirectory — those answer in chat without opening a window.")
    public String openFolderInExplorer(
            @ToolParam(description = "Full path to the folder to open in File Explorer") String folderPath) {
        notifier.notify("Opening " + folderPath + " in File Explorer...");
        try {
            Path p = Paths.get(folderPath).toAbsolutePath();
            if (!Files.exists(p)) return "Path not found: " + p;
            if (!Files.isDirectory(p)) return "Not a folder: " + p + " — use openPath for files.";
            new ProcessBuilder("explorer.exe", p.toString()).start();
            return "Opened File Explorer at: " + p;
        } catch (Exception e) {
            return "Failed to open Explorer: " + e.getMessage();
        }
    }

    @Tool(description = "Open a document (Word .docx, PDF, Excel .xlsx, PowerPoint .pptx, text, image, etc.) by name "
            + "— searches Desktop, Documents, Downloads, OneDrive, and recent folders. Partial name matches work. "
            + "Opens the file with its default application (Word, Acrobat, Excel, etc.). "
            + "Use when the user says 'open my Q4 report', 'open the budget spreadsheet', 'show me the resume PDF', "
            + "'open budget.xlsx', 'open that document about X'. "
            + "If multiple files match, lists the top 10 so the user can pick. "
            + "File types supported: .docx .doc .pdf .xlsx .xls .pptx .ppt .txt .md .rtf .odt .ods .odp .csv "
            + ".png .jpg .jpeg .gif .bmp .svg .mp3 .mp4 .mov .avi .zip .7z .html .htm and more.")
    public String openDocument(
            @ToolParam(description = "Document name or part of it, e.g. 'Q4 report', 'resume', 'budget 2026', 'presentation.pptx'")
            String nameOrFragment) {

        if (nameOrFragment == null || nameOrFragment.isBlank()) return "Document name is required.";
        String query = nameOrFragment.trim().toLowerCase();
        notifier.notify("Searching for '" + nameOrFragment + "'...");

        // Search roots — ordered by likely-usefulness
        String home = System.getProperty("user.home");
        List<Path> roots = new ArrayList<>();
        for (String sub : new String[]{"Desktop", "Documents", "Downloads", "OneDrive",
                                        "OneDrive\\Desktop", "OneDrive\\Documents",
                                        "OneDrive - Personal\\Desktop", "OneDrive - Personal\\Documents"}) {
            Path p = Paths.get(home, sub);
            if (Files.isDirectory(p)) roots.add(p);
        }
        if (roots.isEmpty()) roots.add(Paths.get(home));

        // Extensions that count as "documents"
        Set<String> docExts = Set.of(
                "docx", "doc", "pdf", "xlsx", "xls", "pptx", "ppt",
                "txt", "md", "rtf", "odt", "ods", "odp", "csv",
                "png", "jpg", "jpeg", "gif", "bmp", "svg", "webp",
                "mp3", "mp4", "mov", "avi", "mkv", "wav", "flac",
                "zip", "7z", "rar", "tar", "gz",
                "html", "htm", "xml", "json", "yaml", "yml",
                "py", "java", "js", "ts", "cpp", "c", "h", "cs",
                "epub", "mobi", "psd", "ai", "sketch", "fig"
        );

        List<Map.Entry<Path, Long>> matches = new ArrayList<>();
        long cap = 500; // cap files scanned per root to keep it fast
        for (Path root : roots) {
            try (Stream<Path> walk = Files.walk(root, 4)) {
                walk.filter(Files::isRegularFile)
                    .limit(cap)
                    .forEach(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        String ext = getExt(name);
                        if (!docExts.contains(ext)) return;
                        if (!name.contains(query) && !query.contains(stripExt(name))) return;
                        try {
                            long mtime = Files.getLastModifiedTime(p).toMillis();
                            matches.add(Map.entry(p, mtime));
                        } catch (IOException ignored) {}
                    });
            } catch (IOException ignored) {}
        }

        if (matches.isEmpty()) {
            return "No documents matching '" + nameOrFragment + "' found in Desktop, Documents, Downloads, or OneDrive. "
                    + "Try a different name, or provide a full path via openPath.";
        }

        // Sort by recency (most recently modified first)
        matches.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

        // If 1 clear match OR the top match is much more recent, just open it
        if (matches.size() == 1) {
            return openFileWithDefault(matches.get(0).getKey());
        }

        // Multiple matches — prefer exact filename match if present
        for (Map.Entry<Path, Long> e : matches) {
            String nm = e.getKey().getFileName().toString().toLowerCase();
            if (nm.equals(query) || stripExt(nm).equals(query)) {
                return openFileWithDefault(e.getKey());
            }
        }

        // Otherwise open the most recent + show the list
        Path top = matches.get(0).getKey();
        String opened = openFileWithDefault(top);

        StringBuilder sb = new StringBuilder(opened);
        if (matches.size() > 1) {
            sb.append("\n\n").append(matches.size()).append(" documents matched — opened the most recent. Others found:\n");
            int shown = 0;
            for (int i = 1; i < matches.size() && shown < 9; i++) {
                sb.append("  ").append(matches.get(i).getKey()).append("\n");
                shown++;
            }
            if (matches.size() > 10) sb.append("  ... and ").append(matches.size() - 10).append(" more");
        }
        return sb.toString();
    }

    private String openFileWithDefault(Path p) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(p.toFile());
                return "Opened: " + p;
            }
            new ProcessBuilder("cmd", "/c", "start", "", p.toString()).start();
            return "Opened: " + p;
        } catch (Exception e) {
            return "Failed to open " + p + ": " + e.getMessage();
        }
    }

    private static String getExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /**
     * Resolve a directory string to one or more real paths.
     * Handles:
     *  - Full absolute paths as-is
     *  - Shortcuts like "Desktop", "Downloads", "Documents", "Pictures", "Videos", "Music", "OneDrive"
     *  - Windows OneDrive-redirected Desktop/Documents (both user-home and OneDrive locations are returned if they exist)
     */
    private List<Path> resolveDirectoryRoots(String input) {
        List<Path> out = new ArrayList<>();
        if (input == null || input.isBlank()) return out;
        String raw = input.trim();

        // If it looks like a full path (drive letter or starts with /), try it directly first
        Path direct = Paths.get(raw).toAbsolutePath();
        if (Files.isDirectory(direct)) {
            out.add(direct);
            return out;
        }

        String home = System.getProperty("user.home");
        String lower = raw.toLowerCase();

        // Normalize common shortcuts
        String sub = switch (lower) {
            case "desktop" -> "Desktop";
            case "downloads", "download" -> "Downloads";
            case "documents", "my documents" -> "Documents";
            case "pictures" -> "Pictures";
            case "videos", "movies" -> "Videos";
            case "music" -> "Music";
            case "onedrive" -> "OneDrive";
            default -> null;
        };

        if (sub != null) {
            // Include both the user-home location AND the OneDrive-redirected version
            Path userHomePath = Paths.get(home, sub);
            if (Files.isDirectory(userHomePath)) out.add(userHomePath);
            for (String oneDriveRoot : new String[]{"OneDrive", "OneDrive - Personal"}) {
                Path od = Paths.get(home, oneDriveRoot, sub);
                if (Files.isDirectory(od) && !out.contains(od)) out.add(od);
            }
        }

        return out;
    }

    // ─── Search ──────────────────────────────────────────────────────────────

    @Tool(description = "Search for files or directories by name pattern within a starting directory. Supports wildcards * and ?. "
            + "Returns the total count of ALL matches and lists the first 100 with details. "
            + "Matching is case-insensitive, so '*.pdf' finds both .pdf and .PDF files. "
            + "You can pass shortcuts like 'Desktop', 'Downloads', 'Documents' instead of full paths — "
            + "they resolve to the user's home folder AND the OneDrive-redirected location if present. "
            + "USE THIS when the user asks to find/search for files on their PC.")
    public String searchInDirectory(
            @ToolParam(description = "Directory to search in — full path OR shortcut ('Desktop', 'Downloads', 'Documents', 'OneDrive')") String directory,
            @ToolParam(description = "Name pattern, e.g. '*.pdf', 'report*', '*.log'. Case-insensitive.") String pattern) {
        notifier.notify("Searching for " + pattern + " in " + directory + "...");

        // Resolve directory — handle shortcuts + OneDrive-redirected paths
        List<Path> roots = resolveDirectoryRoots(directory);
        if (roots.isEmpty()) return "Directory not found: " + directory;

        try {
            // Case-insensitive glob: normalize to lowercase and let the matcher operate on lowercase names
            String normalizedPattern = pattern.toLowerCase();
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + normalizedPattern);

            StringBuilder sb = new StringBuilder();
            int[] listed = {0};
            AtomicLong totalCount = new AtomicLong(0);

            for (Path dir : roots) {
                Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String lower = file.getFileName().toString().toLowerCase();
                        if (matcher.matches(Paths.get(lower))) {
                            totalCount.incrementAndGet();
                            if (listed[0] < 100) {
                                sb.append(file).append(" (").append(formatSize(attrs.size())).append(")\n");
                                listed[0]++;
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                    @Override
                    public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                        if (!d.equals(dir)) {
                            String lower = d.getFileName().toString().toLowerCase();
                            if (matcher.matches(Paths.get(lower))) {
                                totalCount.incrementAndGet();
                                if (listed[0] < 100) {
                                    sb.append(d).append(" (DIR)\n");
                                    listed[0]++;
                                }
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            }

            long total = totalCount.get();
            if (total == 0) {
                String locations = roots.stream().map(Path::toString)
                        .reduce((a, b) -> a + " and " + b).orElse(directory);
                return "No matches found for '" + pattern + "' in " + locations;
            }
            String header = "Found " + total + " match(es)";
            if (total > 100) {
                header += " (showing first 100)";
            }
            if (roots.size() > 1) {
                header += " across " + roots.size() + " locations";
            }
            return header + ":\n" + sb;
        } catch (IOException e) {
            return "Search failed: " + e.getMessage();
        }
    }

    // ─── Zip / Unzip ─────────────────────────────────────────────────────────

    @Tool(description = "Compress a file or directory into a zip archive")
    public String zipPath(
            @ToolParam(description = "Path to the file or directory to compress") String sourcePath,
            @ToolParam(description = "Output zip file path, e.g. C:\\Users\\me\\archive.zip") String zipFilePath) {
        notifier.notify("Zipping " + sourcePath + "...");
        try {
            Path src = Paths.get(sourcePath).toAbsolutePath();
            Path zipFile = Paths.get(zipFilePath).toAbsolutePath();
            if (!Files.exists(src)) return "Source not found: " + src;
            Files.createDirectories(zipFile.getParent());

            AtomicLong count = new AtomicLong();
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
                if (Files.isDirectory(src)) {
                    Files.walkFileTree(src, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            String entryName = src.relativize(file).toString().replace('\\', '/');
                            zos.putNextEntry(new ZipEntry(entryName));
                            Files.copy(file, zos);
                            zos.closeEntry();
                            count.incrementAndGet();
                            return FileVisitResult.CONTINUE;
                        }
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                            if (!dir.equals(src)) {
                                String entryName = src.relativize(dir).toString().replace('\\', '/') + "/";
                                zos.putNextEntry(new ZipEntry(entryName));
                                zos.closeEntry();
                            }
                            return FileVisitResult.CONTINUE;
                        }
                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
                } else {
                    zos.putNextEntry(new ZipEntry(src.getFileName().toString()));
                    Files.copy(src, zos);
                    zos.closeEntry();
                    count.set(1);
                }
            }
            return "Created " + zipFile + " (" + count.get() + " files, " + formatSize(Files.size(zipFile)) + ")";
        } catch (IOException e) {
            return "Zip failed: " + e.getMessage();
        }
    }

    @Tool(description = "Extract a zip archive to a destination directory")
    public String unzipFile(
            @ToolParam(description = "Path to the .zip file") String zipFilePath,
            @ToolParam(description = "Directory to extract into") String destinationPath) {
        notifier.notify("Unzipping " + zipFilePath + "...");
        try {
            Path zipFile = Paths.get(zipFilePath).toAbsolutePath();
            Path destDir = Paths.get(destinationPath).toAbsolutePath();
            if (!Files.exists(zipFile)) return "Zip file not found: " + zipFile;
            Files.createDirectories(destDir);

            int count = 0;
            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
                ZipEntry entry;
                byte[] buffer = new byte[8192];
                while ((entry = zis.getNextEntry()) != null) {
                    Path target = destDir.resolve(entry.getName()).normalize();
                    // Path traversal protection
                    if (!target.startsWith(destDir)) {
                        continue;
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        try (OutputStream out = Files.newOutputStream(target)) {
                            int len;
                            while ((len = zis.read(buffer)) > 0) out.write(buffer, 0, len);
                        }
                        count++;
                    }
                    zis.closeEntry();
                }
            }
            return "Extracted " + count + " files to " + destDir;
        } catch (IOException e) {
            return "Unzip failed: " + e.getMessage();
        }
    }

    // ─── Date-filtered activity ──────────────────────────────────────────────

    @Tool(description = "Count files in a folder filtered by creation or modification date. "
            + "Use when the user asks 'how many files created today', 'files modified this week', "
            + "'how many Downloads from yesterday'. Replaces ad-hoc PowerShell scripts.")
    public String countFilesByDate(
            @ToolParam(description = "Folder to count files in") String folder,
            @ToolParam(description = "Date filter: 'today', 'yesterday', 'week' (last 7 days), 'month' (last 30 days), 'year' (last 365 days), or an ISO date 'YYYY-MM-DD'") String dateFilter,
            @ToolParam(description = "'created' or 'modified' — which date to filter on") String dateType,
            @ToolParam(description = "true to count files in all subfolders recursively, false for top-level only") boolean recursive) {
        notifier.notify("Counting " + dateType + " files in " + folder + " (" + dateFilter + ")...");
        try {
            Path dir = Paths.get(folder).toAbsolutePath();
            if (!Files.isDirectory(dir)) return "Not a directory: " + dir;

            DateRange range = parseDateFilter(dateFilter);
            if (range == null) return "Invalid date filter: '" + dateFilter + "'. Use today, yesterday, week, month, year, or YYYY-MM-DD.";
            boolean useCreated = resolveDateType(dateType);

            AtomicLong matched = new AtomicLong();
            AtomicLong totalSize = new AtomicLong();

            if (recursive) {
                Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (isInRange(attrs, range, useCreated)) {
                            matched.incrementAndGet();
                            totalSize.addAndGet(attrs.size());
                        }
                        return FileVisitResult.CONTINUE;
                    }
                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } else {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                    for (Path entry : stream) {
                        if (!Files.isRegularFile(entry)) continue;
                        BasicFileAttributes attrs = Files.readAttributes(entry, BasicFileAttributes.class);
                        if (isInRange(attrs, range, useCreated)) {
                            matched.incrementAndGet();
                            totalSize.addAndGet(attrs.size());
                        }
                    }
                }
            }

            return matched.get() + " file(s) " + dateType + " " + describeRange(range)
                    + " in " + dir + " (" + (recursive ? "recursive" : "top-level")
                    + ", total " + formatSize(totalSize.get()) + ").";
        } catch (IOException e) {
            return "Count failed: " + e.getMessage();
        }
    }

    @Tool(description = "Count files in EACH immediate subfolder of the given parent, filtered by creation "
            + "or modification date. Returns a per-subfolder breakdown. "
            + "Use when the user asks 'break down files created today by folder', 'how many files in each "
            + "subfolder from this week', 'Downloads activity by subfolder'. Replaces ad-hoc PowerShell scripts.")
    public String countFilesPerSubfolder(
            @ToolParam(description = "Parent folder whose subfolders will be scanned") String parentFolder,
            @ToolParam(description = "Date filter: 'today', 'yesterday', 'week', 'month', 'year', 'all', or ISO 'YYYY-MM-DD'") String dateFilter,
            @ToolParam(description = "'created' or 'modified' — which date to filter on. Ignored when dateFilter is 'all'") String dateType) {
        notifier.notify("Counting " + dateType + " files per subfolder in " + parentFolder + "...");
        try {
            Path dir = Paths.get(parentFolder).toAbsolutePath();
            if (!Files.isDirectory(dir)) return "Not a directory: " + dir;

            boolean countAll = "all".equalsIgnoreCase(dateFilter);
            DateRange range = countAll ? null : parseDateFilter(dateFilter);
            if (!countAll && range == null) {
                return "Invalid date filter: '" + dateFilter + "'. Use today, yesterday, week, month, year, all, or YYYY-MM-DD.";
            }
            boolean useCreated = countAll || resolveDateType(dateType);

            Map<String, long[]> perFolder = new TreeMap<>();  // name → [count, size]
            long grandCount = 0, grandSize = 0;

            try (DirectoryStream<Path> subdirs = Files.newDirectoryStream(dir, Files::isDirectory)) {
                for (Path sub : subdirs) {
                    long[] stats = {0, 0};
                    Files.walkFileTree(sub, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (countAll || isInRange(attrs, range, useCreated)) {
                                stats[0]++;
                                stats[1] += attrs.size();
                            }
                            return FileVisitResult.CONTINUE;
                        }
                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
                    perFolder.put(sub.getFileName().toString(), stats);
                    grandCount += stats[0];
                    grandSize += stats[1];
                }
            }

            if (perFolder.isEmpty()) return "No subfolders found in " + dir;

            String header = countAll
                    ? "Files per subfolder in " + dir + " (all):\n"
                    : "Files " + dateType + " " + describeRange(range) + " per subfolder in " + dir + ":\n";
            StringBuilder sb = new StringBuilder(header);
            for (Map.Entry<String, long[]> e : perFolder.entrySet()) {
                long[] s = e.getValue();
                sb.append(String.format("  %-24s %6d file(s)  %s%n",
                        e.getKey(), s[0], formatSize(s[1])));
            }
            sb.append(String.format("  %-24s %6d file(s)  %s%n",
                    "Total:", grandCount, formatSize(grandSize)));
            return sb.toString();
        } catch (IOException e) {
            return "Per-subfolder count failed: " + e.getMessage();
        }
    }

    @Tool(description = "List files filtered by creation or modification date, with name, size, and date. "
            + "Use when the user asks 'show me files created today', 'what did I modify this week', "
            + "'list Downloads from yesterday'.")
    public String listFilesByDate(
            @ToolParam(description = "Folder to scan") String folder,
            @ToolParam(description = "Date filter: 'today', 'yesterday', 'week', 'month', 'year', or ISO 'YYYY-MM-DD'") String dateFilter,
            @ToolParam(description = "'created' or 'modified'") String dateType,
            @ToolParam(description = "true to search subfolders recursively, false for top-level only") boolean recursive) {
        notifier.notify("Listing " + dateType + " files in " + folder + " (" + dateFilter + ")...");
        try {
            Path dir = Paths.get(folder).toAbsolutePath();
            if (!Files.isDirectory(dir)) return "Not a directory: " + dir;

            DateRange range = parseDateFilter(dateFilter);
            if (range == null) return "Invalid date filter: '" + dateFilter + "'. Use today, yesterday, week, month, year, or YYYY-MM-DD.";
            boolean useCreated = resolveDateType(dateType);

            List<String> matches = new ArrayList<>();
            int[] total = {0};

            if (recursive) {
                Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (isInRange(attrs, range, useCreated)) {
                            total[0]++;
                            if (matches.size() < 200) {
                                Instant when = useCreated
                                        ? attrs.creationTime().toInstant()
                                        : attrs.lastModifiedTime().toInstant();
                                matches.add(String.format("  %s  %s  %s",
                                        FMT.format(when), formatSize(attrs.size()), file));
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } else {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                    for (Path entry : stream) {
                        if (!Files.isRegularFile(entry)) continue;
                        BasicFileAttributes attrs = Files.readAttributes(entry, BasicFileAttributes.class);
                        if (isInRange(attrs, range, useCreated)) {
                            total[0]++;
                            if (matches.size() < 200) {
                                Instant when = useCreated
                                        ? attrs.creationTime().toInstant()
                                        : attrs.lastModifiedTime().toInstant();
                                matches.add(String.format("  %s  %s  %s",
                                        FMT.format(when), formatSize(attrs.size()), entry));
                            }
                        }
                    }
                }
            }

            if (total[0] == 0) {
                return "No files " + dateType + " " + describeRange(range) + " in " + dir;
            }
            StringBuilder sb = new StringBuilder();
            sb.append(total[0]).append(" file(s) ").append(dateType).append(" ").append(describeRange(range))
                    .append(" in ").append(dir);
            if (total[0] > matches.size()) sb.append(" (showing first ").append(matches.size()).append(")");
            sb.append(":\n").append(String.join("\n", matches));
            return sb.toString();
        } catch (IOException e) {
            return "List failed: " + e.getMessage();
        }
    }

    // ─── Date-filter helpers ─────────────────────────────────────────────────

    private record DateRange(Instant start, Instant endExclusive, String label) {}

    private DateRange parseDateFilter(String filter) {
        if (filter == null) return null;
        String f = filter.trim().toLowerCase();
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);

        switch (f) {
            case "today" -> {
                return new DateRange(
                        today.atStartOfDay(zone).toInstant(),
                        today.plusDays(1).atStartOfDay(zone).toInstant(),
                        "today");
            }
            case "yesterday" -> {
                return new DateRange(
                        today.minusDays(1).atStartOfDay(zone).toInstant(),
                        today.atStartOfDay(zone).toInstant(),
                        "yesterday");
            }
            case "week" -> {
                return new DateRange(
                        today.minusDays(7).atStartOfDay(zone).toInstant(),
                        today.plusDays(1).atStartOfDay(zone).toInstant(),
                        "in the last 7 days");
            }
            case "month" -> {
                return new DateRange(
                        today.minusDays(30).atStartOfDay(zone).toInstant(),
                        today.plusDays(1).atStartOfDay(zone).toInstant(),
                        "in the last 30 days");
            }
            case "year" -> {
                return new DateRange(
                        today.minusDays(365).atStartOfDay(zone).toInstant(),
                        today.plusDays(1).atStartOfDay(zone).toInstant(),
                        "in the last 365 days");
            }
        }

        // Try ISO date
        try {
            LocalDate d = LocalDate.parse(f);
            return new DateRange(
                    d.atStartOfDay(zone).toInstant(),
                    d.plusDays(1).atStartOfDay(zone).toInstant(),
                    "on " + d);
        } catch (Exception ignored) {}
        return null;
    }

    private boolean isInRange(BasicFileAttributes attrs, DateRange range, boolean useCreated) {
        Instant when = useCreated ? attrs.creationTime().toInstant() : attrs.lastModifiedTime().toInstant();
        return !when.isBefore(range.start()) && when.isBefore(range.endExclusive());
    }

    private String describeRange(DateRange range) {
        return range.label();
    }

    /** Returns true for 'created'/'create', false for 'modified'/'modify'. Defaults to modified. */
    private boolean resolveDateType(String dateType) {
        if (dateType == null) return false;
        String t = dateType.trim().toLowerCase();
        return t.startsWith("create");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        double gb = mb / 1024.0;
        return String.format("%.2f GB", gb);
    }
}
