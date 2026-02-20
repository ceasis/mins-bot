package com.botsfer.skills.diskscan;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class DiskScanService {

    private final DiskScanConfig.DiskScanProperties properties;

    private static final Set<String> ALWAYS_BLOCKED = Set.of(
            "windows\\system32",
            "windows\\syswow64",
            "system volume information",
            "$recycle.bin",
            "\\appdata\\local\\temp",
            "\\programdata\\microsoft\\windows\\",
            "/etc/shadow",
            "/etc/passwd",
            "/proc",
            "/sys"
    );

    public DiskScanService(DiskScanConfig.DiskScanProperties properties) {
        this.properties = properties;
    }

    public List<Map<String, Object>> listRoots() {
        List<Map<String, Object>> roots = new ArrayList<>();
        for (Path root : FileSystems.getDefault().getRootDirectories()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("path", root.toString());
            try {
                FileStore store = Files.getFileStore(root);
                long total = store.getTotalSpace();
                long free = store.getUnallocatedSpace();
                long usable = store.getUsableSpace();
                info.put("totalBytes", total);
                info.put("freeBytes", free);
                info.put("usableBytes", usable);
                info.put("totalFormatted", formatSize(total));
                info.put("freeFormatted", formatSize(free));
            } catch (IOException e) {
                info.put("error", "Inaccessible");
            }
            roots.add(info);
        }
        return roots;
    }

    public Map<String, Object> browse(String pathStr) throws IOException {
        if (pathStr == null || pathStr.isBlank()) {
            throw new IllegalArgumentException("Path must not be blank");
        }
        Path path = Paths.get(pathStr).toAbsolutePath().normalize();
        if (isBlocked(path)) {
            throw new IllegalArgumentException("Access denied to this path");
        }
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("Path is not a directory");
        }

        List<Map<String, Object>> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
            for (Path child : stream) {
                if (!isBlocked(child)) {
                    children.add(toFileInfo(child));
                }
            }
        }

        children.sort((a, b) -> {
            String typeA = (String) a.get("type");
            String typeB = (String) b.get("type");
            if ("directory".equals(typeA) && !"directory".equals(typeB)) return -1;
            if (!"directory".equals(typeA) && "directory".equals(typeB)) return 1;
            return String.CASE_INSENSITIVE_ORDER.compare(
                    (String) a.get("name"), (String) b.get("name"));
        });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", path.toString());
        result.put("parent", path.getParent() != null ? path.getParent().toString() : null);
        result.put("children", children);
        return result;
    }

    public Map<String, Object> getInfo(String pathStr) throws IOException {
        if (pathStr == null || pathStr.isBlank()) {
            throw new IllegalArgumentException("Path must not be blank");
        }
        Path path = Paths.get(pathStr).toAbsolutePath().normalize();
        if (isBlocked(path)) {
            throw new IllegalArgumentException("Access denied to this path");
        }

        Map<String, Object> info = toFileInfo(path);
        info.put("absolutePath", path.toAbsolutePath().toString());
        info.put("readable", Files.isReadable(path));
        info.put("writable", Files.isWritable(path));
        try {
            info.put("hidden", Files.isHidden(path));
        } catch (IOException e) {
            info.put("hidden", false);
        }
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                int count = 0;
                for (Path ignored : stream) count++;
                info.put("childCount", count);
            }
        }
        return info;
    }

    public Map<String, Object> search(String basePath, String pattern) throws IOException {
        if (basePath == null || basePath.isBlank()) {
            throw new IllegalArgumentException("Base path must not be blank");
        }
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("Pattern must not be blank");
        }
        Path base = Paths.get(basePath).toAbsolutePath().normalize();
        if (isBlocked(base)) {
            throw new IllegalArgumentException("Access denied to this path");
        }
        if (!Files.isDirectory(base)) {
            throw new IllegalArgumentException("Base path is not a directory");
        }

        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        List<Map<String, Object>> results = new ArrayList<>();
        AtomicInteger maxResults = new AtomicInteger(properties.getMaxResults());
        boolean[] truncated = {false};

        Files.walkFileTree(base, EnumSet.noneOf(FileVisitOption.class), properties.getMaxDepth(),
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (isBlocked(dir)) return FileVisitResult.SKIP_SUBTREE;
                        if (maxResults.get() <= 0) return FileVisitResult.TERMINATE;
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (maxResults.get() <= 0) {
                            truncated[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        if (matcher.matches(file.getFileName())) {
                            results.add(toFileInfo(file));
                            maxResults.decrementAndGet();
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("basePath", base.toString());
        result.put("pattern", pattern);
        result.put("resultCount", results.size());
        result.put("truncated", truncated[0]);
        result.put("results", results);
        return result;
    }

    private boolean isBlocked(Path path) {
        String normalized = path.toAbsolutePath().toString().toLowerCase().replace('/', '\\');
        for (String blocked : ALWAYS_BLOCKED) {
            if (normalized.contains(blocked)) return true;
        }
        for (String blocked : properties.getBlockedPaths()) {
            String norm = blocked.toLowerCase().replace('/', '\\');
            if (normalized.startsWith(norm) || normalized.contains(norm)) return true;
        }
        return false;
    }

    private Map<String, Object> toFileInfo(Path path) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", path.getFileName() != null ? path.getFileName().toString() : path.toString());
        info.put("path", path.toAbsolutePath().toString());

        if (Files.isSymbolicLink(path)) {
            info.put("type", "symlink");
        } else if (Files.isDirectory(path)) {
            info.put("type", "directory");
        } else {
            info.put("type", "file");
        }

        try {
            long size = Files.isDirectory(path) ? 0 : Files.size(path);
            info.put("size", size);
            info.put("sizeFormatted", formatSize(size));
        } catch (IOException e) {
            info.put("size", 0);
            info.put("sizeFormatted", "N/A");
        }

        try {
            info.put("lastModified", Files.getLastModifiedTime(path).toInstant().toString());
        } catch (IOException e) {
            info.put("lastModified", null);
        }

        return info;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        double gb = mb / 1024.0;
        if (gb < 1024) return String.format("%.1f GB", gb);
        double tb = gb / 1024.0;
        return String.format("%.1f TB", tb);
    }
}
