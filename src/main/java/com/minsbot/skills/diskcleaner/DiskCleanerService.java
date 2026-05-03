package com.minsbot.skills.diskcleaner;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Identifies large files + known temp/cache directories. Reports sizes only.
 * Deletion requires explicit /delete call with the full path.
 */
@Service
public class DiskCleanerService {
    private final DiskCleanerConfig.DiskCleanerProperties props;
    public DiskCleanerService(DiskCleanerConfig.DiskCleanerProperties props) { this.props = props; }

    public Map<String, Object> tempDirs() {
        List<Map<String, Object>> dirs = new ArrayList<>();
        String home = System.getProperty("user.home");
        String tmp = System.getProperty("java.io.tmpdir");
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        List<String> candidates = new ArrayList<>(List.of(tmp));
        if (win) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null) {
                candidates.add(localAppData + "\\Temp");
                candidates.add(localAppData + "\\Microsoft\\Windows\\INetCache");
                candidates.add(localAppData + "\\Google\\Chrome\\User Data\\Default\\Cache");
                candidates.add(localAppData + "\\Mozilla\\Firefox\\Profiles");
                candidates.add(localAppData + "\\npm-cache");
            }
        } else {
            candidates.add(home + "/.cache");
            candidates.add(home + "/.npm");
            candidates.add(home + "/.gradle/caches");
            candidates.add(home + "/.m2/repository");
        }
        for (String c : candidates) {
            Path p = Paths.get(c);
            if (Files.exists(p)) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("path", p.toAbsolutePath().toString());
                try { d.put("sizeBytes", dirSize(p, 5)); } catch (Exception e) { d.put("error", e.getMessage()); }
                dirs.add(d);
            }
        }
        dirs.sort((a, b) -> Long.compare(((Number) b.getOrDefault("sizeBytes", 0)).longValue(),
                ((Number) a.getOrDefault("sizeBytes", 0)).longValue()));
        return Map.of("dirs", dirs);
    }

    public Map<String, Object> bigFiles(String basePath, int limit) throws IOException {
        Path base = Paths.get(basePath);
        if (!Files.exists(base)) throw new IllegalArgumentException("path does not exist: " + basePath);
        long min = props.getMinFileBytes();
        List<Map<String, Object>> files = new ArrayList<>();
        Files.walkFileTree(base, EnumSet.noneOf(FileVisitOption.class), 12, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && attrs.size() >= min) {
                    files.add(Map.of(
                            "path", file.toAbsolutePath().toString(),
                            "sizeBytes", attrs.size(),
                            "modified", attrs.lastModifiedTime().toString()));
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path f, IOException e) { return FileVisitResult.CONTINUE; }
        });
        files.sort((a, b) -> Long.compare(((Number) b.get("sizeBytes")).longValue(),
                ((Number) a.get("sizeBytes")).longValue()));
        List<Map<String, Object>> trimmed = files.size() > limit ? files.subList(0, limit) : files;
        return Map.of("basePath", base.toAbsolutePath().toString(), "minBytes", min, "files", trimmed);
    }

    public Map<String, Object> deletePath(String path, boolean recursive) throws IOException {
        Path p = Paths.get(path);
        if (!Files.exists(p)) throw new IllegalArgumentException("not found: " + path);
        long size = 0;
        if (Files.isDirectory(p)) {
            if (!recursive) throw new IllegalArgumentException("path is a directory; pass recursive=true");
            size = dirSize(p, 12);
            Files.walkFileTree(p, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes a) throws IOException { Files.delete(file); return FileVisitResult.CONTINUE; }
                @Override public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException { Files.delete(d); return FileVisitResult.CONTINUE; }
            });
        } else {
            size = Files.size(p);
            Files.delete(p);
        }
        return Map.of("ok", true, "deleted", path, "freedBytes", size);
    }

    private static long dirSize(Path dir, int maxDepth) throws IOException {
        long[] total = {0};
        Files.walkFileTree(dir, EnumSet.noneOf(FileVisitOption.class), maxDepth, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) { total[0] += a.size(); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult visitFileFailed(Path f, IOException e) { return FileVisitResult.CONTINUE; }
        });
        return total[0];
    }
}
