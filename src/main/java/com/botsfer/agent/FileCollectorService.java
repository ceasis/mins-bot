package com.botsfer.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class FileCollectorService {

    private static final Logger log = LoggerFactory.getLogger(FileCollectorService.class);

    private static final Path COLLECT_BASE = Paths.get(System.getProperty("user.home"), "botsfer_data", "collected");

    private static final Set<String> SKIP_DIRS = Set.of(
            "windows", "program files", "program files (x86)", "$recycle.bin",
            "system volume information", "programdata", "recovery",
            "appdata\\local\\temp", "appdata\\local\\microsoft",
            "node_modules", ".git", ".gradle", ".m2", "target", "build"
    );

    public static final Map<String, Set<String>> FILE_CATEGORIES = new LinkedHashMap<>();

    static {
        FILE_CATEGORIES.put("photos", Set.of(
                ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".tiff", ".tif",
                ".heic", ".heif", ".svg", ".ico", ".raw", ".cr2", ".nef", ".arw"
        ));
        FILE_CATEGORIES.put("videos", Set.of(
                ".mp4", ".avi", ".mkv", ".mov", ".wmv", ".flv", ".webm", ".m4v",
                ".mpg", ".mpeg", ".3gp", ".ts"
        ));
        FILE_CATEGORIES.put("music", Set.of(
                ".mp3", ".wav", ".flac", ".aac", ".ogg", ".wma", ".m4a", ".opus"
        ));
        FILE_CATEGORIES.put("documents", Set.of(
                ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
                ".txt", ".rtf", ".odt", ".ods", ".odp", ".csv"
        ));
        FILE_CATEGORIES.put("archives", Set.of(
                ".zip", ".rar", ".7z", ".tar", ".gz", ".bz2", ".xz"
        ));
    }

    /**
     * Collects files of a given category from all drives into ~/botsfer_data/collected/<category>/
     * Returns a summary of what was done.
     */
    public String collectByCategory(String category) {
        Set<String> extensions = FILE_CATEGORIES.get(category.toLowerCase());
        if (extensions == null) {
            return "Unknown category: " + category + ". Available: " + String.join(", ", FILE_CATEGORIES.keySet());
        }
        return collectFiles(category.toLowerCase(), extensions);
    }

    /**
     * Collects files matching custom extensions.
     */
    public String collectByExtensions(String label, Set<String> extensions) {
        return collectFiles(label, extensions);
    }

    private String collectFiles(String label, Set<String> extensions) {
        Path destDir;
        try {
            destDir = COLLECT_BASE.resolve(label);
            Files.createDirectories(destDir);
        } catch (IOException e) {
            return "Failed to create destination folder: " + e.getMessage();
        }

        log.info("Collecting '{}' files to {}", label, destDir);

        List<Path> scanRoots = getScanRoots();
        AtomicInteger found = new AtomicInteger(0);
        AtomicInteger copied = new AtomicInteger(0);
        AtomicInteger errors = new AtomicInteger(0);
        AtomicLong totalBytes = new AtomicLong(0);

        for (Path root : scanRoots) {
            try {
                Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 30,
                        new SimpleFileVisitor<>() {
                            @Override
                            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                                if (shouldSkipDir(dir)) return FileVisitResult.SKIP_SUBTREE;
                                return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                                String name = file.getFileName().toString().toLowerCase();
                                int dot = name.lastIndexOf('.');
                                if (dot < 0) return FileVisitResult.CONTINUE;
                                String ext = name.substring(dot);
                                if (!extensions.contains(ext)) return FileVisitResult.CONTINUE;

                                found.incrementAndGet();
                                try {
                                    // Preserve uniqueness: drive_relativePath
                                    String uniqueName = buildUniqueName(file);
                                    Path dest = destDir.resolve(uniqueName);
                                    if (Files.exists(dest)) {
                                        // Skip if same size (likely same file)
                                        if (Files.size(dest) == attrs.size()) return FileVisitResult.CONTINUE;
                                        // Add counter suffix
                                        dest = deduplicate(dest);
                                    }
                                    Files.createDirectories(dest.getParent());
                                    Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
                                    copied.incrementAndGet();
                                    totalBytes.addAndGet(attrs.size());
                                } catch (IOException e) {
                                    errors.incrementAndGet();
                                }
                                return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                                return FileVisitResult.CONTINUE;
                            }
                        });
            } catch (IOException e) {
                log.warn("Error scanning root {}: {}", root, e.getMessage());
            }
        }

        String summary = String.format(
                "Done! Scanned for %s files.\n- Found: %d\n- Copied: %d\n- Errors: %d\n- Total size: %s\n- Saved to: %s",
                label, found.get(), copied.get(), errors.get(),
                formatSize(totalBytes.get()), destDir.toAbsolutePath()
        );
        log.info(summary);
        return summary;
    }

    /**
     * Search for files matching a glob pattern in user directories.
     */
    public String searchFiles(String pattern, int maxResults) {
        List<Path> scanRoots = getScanRoots();
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        List<String> results = new ArrayList<>();
        AtomicInteger remaining = new AtomicInteger(maxResults > 0 ? maxResults : 100);

        for (Path root : scanRoots) {
            if (remaining.get() <= 0) break;
            try {
                Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 20,
                        new SimpleFileVisitor<>() {
                            @Override
                            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                                if (shouldSkipDir(dir)) return FileVisitResult.SKIP_SUBTREE;
                                if (remaining.get() <= 0) return FileVisitResult.TERMINATE;
                                return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                                if (remaining.get() <= 0) return FileVisitResult.TERMINATE;
                                if (matcher.matches(file.getFileName())) {
                                    results.add(file.toAbsolutePath().toString());
                                    remaining.decrementAndGet();
                                }
                                return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                                return FileVisitResult.CONTINUE;
                            }
                        });
            } catch (IOException e) {
                // skip this root
            }
        }

        if (results.isEmpty()) {
            return "No files found matching: " + pattern;
        }
        StringBuilder sb = new StringBuilder("Found " + results.size() + " file(s):\n");
        for (String path : results) {
            sb.append("  ").append(path).append("\n");
        }
        return sb.toString();
    }

    private List<Path> getScanRoots() {
        List<Path> roots = new ArrayList<>();
        // User home directories are the primary scan target
        Path home = Paths.get(System.getProperty("user.home"));
        roots.add(home);

        // Also scan other drive roots (Windows), but only user-accessible folders
        for (Path fsRoot : FileSystems.getDefault().getRootDirectories()) {
            if (!home.startsWith(fsRoot) || !home.getRoot().equals(fsRoot)) {
                // Different drive — scan its root but shouldSkipDir filters system dirs
                roots.add(fsRoot);
            }
        }
        return roots;
    }

    private boolean shouldSkipDir(Path dir) {
        String abs = dir.toAbsolutePath().toString().toLowerCase();
        for (String skip : SKIP_DIRS) {
            if (abs.contains(skip.toLowerCase())) return true;
        }
        // Skip hidden directories (starting with .)
        Path fileName = dir.getFileName();
        if (fileName != null && fileName.toString().startsWith(".") && !fileName.toString().equals(".")) {
            return true;
        }
        return false;
    }

    private String buildUniqueName(Path file) {
        Path root = file.getRoot();
        String rootStr = root != null ? root.toString().replace(":", "").replace("\\", "").replace("/", "") : "";
        Path relative = root != null ? root.relativize(file) : file;
        // Flatten path: C:\Users\name\pic.jpg -> C_Users_name_pic.jpg
        return rootStr + "_" + relative.toString().replace("\\", "_").replace("/", "_");
    }

    private Path deduplicate(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int counter = 1;
        Path parent = path.getParent();
        Path candidate;
        do {
            candidate = parent.resolve(base + "_" + counter + ext);
            counter++;
        } while (Files.exists(candidate));
        return candidate;
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
