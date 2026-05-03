package com.minsbot.skills.logtail;

import org.springframework.stereotype.Service;

import java.io.RandomAccessFile;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Returns the last N lines of a log file with optional regex filter.
 * Reads the file backward to avoid loading the whole thing.
 */
@Service
public class LogTailService {
    private final LogTailConfig.LogTailProperties props;
    public LogTailService(LogTailConfig.LogTailProperties props) { this.props = props; }

    public Map<String, Object> tail(String path, int lines, String filter) throws Exception {
        Path p = Paths.get(path);
        if (!Files.isRegularFile(p)) throw new IllegalArgumentException("not a file: " + path);
        long size = Files.size(p);
        if (size > props.getMaxFileBytes())
            throw new RuntimeException("file too big (" + size + " bytes); raise app.skills.logtail.max-file-bytes");
        Pattern re = (filter == null || filter.isBlank()) ? null : Pattern.compile(filter);

        List<String> result = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "r")) {
            long pos = size;
            int targetLines = Math.max(lines, 1);
            int needed = re == null ? targetLines : targetLines * 4; // overscan when filtering
            StringBuilder cur = new StringBuilder();
            while (pos > 0 && result.size() < needed) {
                pos--;
                raf.seek(pos);
                int b = raf.read();
                if (b == '\n') {
                    String line = cur.reverse().toString();
                    cur.setLength(0);
                    if (re == null || re.matcher(line).find()) result.add(line);
                } else if (b != '\r') cur.append((char) b);
            }
            if (cur.length() > 0) {
                String line = cur.reverse().toString();
                if (re == null || re.matcher(line).find()) result.add(line);
            }
        }
        Collections.reverse(result);
        if (result.size() > lines) result = result.subList(result.size() - lines, result.size());
        return Map.of("path", p.toAbsolutePath().toString(),
                "totalSize", size, "lines", result.size(), "content", String.join("\n", result));
    }
}
