package com.minsbot.skills.filestats;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Counts files in a directory grouped by extension. Optional recursive walk,
 * optional total bytes per extension, optional dotfile inclusion.
 *
 * Resolves common path tokens: ~, ${HOME}, "Downloads"/"Desktop"/"Documents"
 * (mapped relative to user.home).
 */
@Service
public class FileStatsService {

    public Map<String, Object> countByExtension(String basePath, boolean recursive, boolean includeDotfiles) throws IOException {
        Path base = resolvePath(basePath);
        if (!Files.isDirectory(base)) throw new IllegalArgumentException("not a directory: " + base);

        Map<String, long[]> byExt = new HashMap<>(); // ext -> [count, totalBytes]
        long[] totals = {0, 0}; // [files, bytes]
        long[] dirs = {0};

        FileVisitor<Path> visitor = new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                if (!dir.equals(base)) dirs[0]++;
                return recursive ? FileVisitResult.CONTINUE : (dir.equals(base) ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE);
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                String name = file.getFileName().toString();
                if (!includeDotfiles && name.startsWith(".")) return FileVisitResult.CONTINUE;
                String ext = extOf(name);
                byExt.computeIfAbsent(ext, k -> new long[2]);
                byExt.get(ext)[0]++;
                byExt.get(ext)[1] += a.size();
                totals[0]++;
                totals[1] += a.size();
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path f, IOException e) { return FileVisitResult.CONTINUE; }
        };
        if (recursive) Files.walkFileTree(base, EnumSet.noneOf(FileVisitOption.class), 16, visitor);
        else Files.walkFileTree(base, EnumSet.noneOf(FileVisitOption.class), 1, visitor);

        List<Map<String, Object>> sorted = new ArrayList<>();
        byExt.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .forEach(e -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("extension", e.getKey());
                    r.put("count", e.getValue()[0]);
                    r.put("totalBytes", e.getValue()[1]);
                    r.put("totalMb", Math.round(e.getValue()[1] / 1_000_000.0 * 10) / 10.0);
                    sorted.add(r);
                });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", base.toAbsolutePath().toString());
        result.put("recursive", recursive);
        result.put("includeDotfiles", includeDotfiles);
        result.put("totalFiles", totals[0]);
        result.put("totalBytes", totals[1]);
        result.put("totalMb", Math.round(totals[1] / 1_000_000.0 * 10) / 10.0);
        result.put("subdirectories", dirs[0]);
        result.put("uniqueExtensions", sorted.size());
        result.put("byExtension", sorted);
        return result;
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return "(none)";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static Path resolvePath(String input) {
        if (input == null || input.isBlank()) input = "~";
        String home = System.getProperty("user.home");
        String s = input.trim();
        // Tilde expansion
        if (s.equals("~") || s.startsWith("~/") || s.startsWith("~\\")) s = home + s.substring(1);
        // ${HOME}
        s = s.replace("${HOME}", home).replace("$HOME", home);
        // Common folder shortcuts (case-insensitive)
        String low = s.toLowerCase(Locale.ROOT);
        if (List.of("downloads", "desktop", "documents", "pictures", "videos", "music").contains(low)) {
            s = home + java.io.File.separator + capitalize(low);
        }
        return Paths.get(s);
    }

    private static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
