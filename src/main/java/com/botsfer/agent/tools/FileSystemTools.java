package com.botsfer.agent.tools;

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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
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

    @Tool(description = "List the contents of a directory showing name, type (file/dir), size, and last modified date")
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

    @Tool(description = "Count how many files and directories are in a given directory (non-recursive, top-level only)")
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

    @Tool(description = "Open a file or folder on the PC by its full path in file explorer or default application")
    public String openPath(
            @ToolParam(description = "Full path to the file or folder to open") String path) {
        notifier.notify("Opening " + path + "...");
        try {
            Path p = Paths.get(path).toAbsolutePath();
            if (!Files.exists(p)) return "Path not found: " + p;
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

    // ─── Search ──────────────────────────────────────────────────────────────

    @Tool(description = "Search for files or directories by name pattern within a starting directory. Supports wildcards * and ?. "
            + "Returns the total count of ALL matches and lists the first 100 with details.")
    public String searchInDirectory(
            @ToolParam(description = "Directory to search in") String directory,
            @ToolParam(description = "Name pattern, e.g. '*.txt', 'report*', '*.log'") String pattern) {
        notifier.notify("Searching for " + pattern + " in " + directory + "...");
        try {
            Path dir = Paths.get(directory).toAbsolutePath();
            if (!Files.exists(dir) || !Files.isDirectory(dir)) return "Directory not found: " + dir;
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            StringBuilder sb = new StringBuilder();
            int[] listed = {0};
            AtomicLong totalCount = new AtomicLong(0);
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (matcher.matches(file.getFileName())) {
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
                    if (!d.equals(dir) && matcher.matches(d.getFileName())) {
                        totalCount.incrementAndGet();
                        if (listed[0] < 100) {
                            sb.append(d).append(" (DIR)\n");
                            listed[0]++;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
            long total = totalCount.get();
            if (total == 0) return "No matches found for '" + pattern + "' in " + dir;
            String header = "Found " + total + " match(es)";
            if (total > 100) {
                header += " (showing first 100)";
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
