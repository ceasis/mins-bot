package com.minsbot.skills.bigfilescan;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

@Service
public class BigFileScanService {
    private final BigFileScanConfig.BigFileScanProperties props;
    public BigFileScanService(BigFileScanConfig.BigFileScanProperties props) { this.props = props; }

    public Map<String, Object> scan(String basePath, Long minBytes, Integer limit) throws IOException {
        Path base = Paths.get(basePath);
        if (!Files.exists(base)) throw new IllegalArgumentException("not found: " + basePath);
        long min = minBytes == null ? props.getDefaultMinBytes() : minBytes;
        int n = limit == null ? 50 : limit;
        List<Map<String, Object>> files = new ArrayList<>();
        Files.walkFileTree(base, EnumSet.noneOf(FileVisitOption.class), 12, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                if (a.isRegularFile() && a.size() >= min) {
                    files.add(Map.of("path", f.toAbsolutePath().toString(),
                            "sizeBytes", a.size(),
                            "sizeMb", Math.round(a.size() / 1_000_000.0),
                            "modified", a.lastModifiedTime().toString()));
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path f, IOException e) { return FileVisitResult.CONTINUE; }
        });
        files.sort((a, b) -> Long.compare(((Number) b.get("sizeBytes")).longValue(), ((Number) a.get("sizeBytes")).longValue()));
        List<Map<String, Object>> trimmed = files.size() > n ? files.subList(0, n) : files;
        long total = trimmed.stream().mapToLong(f -> ((Number) f.get("sizeBytes")).longValue()).sum();
        return Map.of("basePath", base.toAbsolutePath().toString(),
                "minBytes", min,
                "totalSizeMb", Math.round(total / 1_000_000.0),
                "files", trimmed);
    }
}
