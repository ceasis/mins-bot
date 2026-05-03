package com.minsbot.skills.duplicatefinder;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

/**
 * Two-pass dupe detection: bucket by size, then SHA-1 only files that share size.
 * Skips files smaller than min-bytes to avoid hashing tiny noise.
 */
@Service
public class DuplicateFinderService {
    private final DuplicateFinderConfig.DuplicateFinderProperties props;
    public DuplicateFinderService(DuplicateFinderConfig.DuplicateFinderProperties props) { this.props = props; }

    public Map<String, Object> find(String basePath) throws IOException {
        Path base = Paths.get(basePath);
        if (!Files.exists(base)) throw new IllegalArgumentException("not found: " + basePath);
        Map<Long, List<Path>> bySize = new HashMap<>();
        Files.walkFileTree(base, EnumSet.noneOf(FileVisitOption.class), 12, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                if (a.isRegularFile() && a.size() >= props.getMinBytes())
                    bySize.computeIfAbsent(a.size(), k -> new ArrayList<>()).add(f);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path f, IOException e) { return FileVisitResult.CONTINUE; }
        });

        Map<String, List<String>> byHash = new LinkedHashMap<>();
        Map<String, Long> hashSize = new HashMap<>();
        for (var e : bySize.entrySet()) {
            if (e.getValue().size() < 2) continue;
            for (Path p : e.getValue()) {
                try {
                    String h = sha1(p);
                    byHash.computeIfAbsent(h, k -> new ArrayList<>()).add(p.toAbsolutePath().toString());
                    hashSize.put(h, e.getKey());
                } catch (Exception ignored) {}
            }
        }

        List<Map<String, Object>> groups = new ArrayList<>();
        long wasted = 0;
        for (var e : byHash.entrySet()) {
            if (e.getValue().size() < 2) continue;
            long size = hashSize.get(e.getKey());
            wasted += size * (e.getValue().size() - 1);
            groups.add(Map.of("hash", e.getKey(), "sizeBytes", size, "files", e.getValue()));
        }
        groups.sort((a, b) -> Long.compare(((Number) b.get("sizeBytes")).longValue(),
                ((Number) a.get("sizeBytes")).longValue()));
        return Map.of("basePath", base.toAbsolutePath().toString(),
                "duplicateGroups", groups,
                "wastedBytes", wasted,
                "wastedMb", Math.round(wasted / 1_000_000.0));
    }

    private static String sha1(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        byte[] d = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
