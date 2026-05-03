package com.minsbot.skills.fileinspect;

import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.*;

@Service
public class FileInspectService {
    private final FileInspectConfig.FileInspectProperties props;
    public FileInspectService(FileInspectConfig.FileInspectProperties props) { this.props = props; }

    public Map<String, Object> head(String path, int n) throws IOException {
        Path p = Paths.get(path);
        check(p);
        List<String> out = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(p)) {
            String line;
            while (out.size() < n && (line = r.readLine()) != null) out.add(line);
        }
        return Map.of("path", p.toAbsolutePath().toString(), "lines", out.size(), "content", String.join("\n", out));
    }

    public Map<String, Object> tail(String path, int n) throws Exception {
        Path p = Paths.get(path);
        check(p);
        long size = Files.size(p);
        List<String> out = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "r")) {
            long pos = size;
            StringBuilder cur = new StringBuilder();
            while (pos > 0 && out.size() < n) {
                pos--;
                raf.seek(pos);
                int b = raf.read();
                if (b == '\n') {
                    out.add(cur.reverse().toString());
                    cur.setLength(0);
                } else if (b != '\r') cur.append((char) b);
            }
            if (cur.length() > 0) out.add(cur.reverse().toString());
        }
        Collections.reverse(out);
        if (out.size() > n) out = out.subList(out.size() - n, out.size());
        return Map.of("path", p.toAbsolutePath().toString(), "lines", out.size(), "content", String.join("\n", out));
    }

    public Map<String, Object> wc(String path) throws IOException {
        Path p = Paths.get(path);
        check(p);
        long lines = 0, words = 0, chars = 0, bytes = Files.size(p);
        try (BufferedReader r = Files.newBufferedReader(p)) {
            String line;
            while ((line = r.readLine()) != null) {
                lines++;
                chars += line.length() + 1;
                if (!line.isBlank()) words += line.trim().split("\\s+").length;
            }
        }
        return Map.of("path", p.toAbsolutePath().toString(),
                "lines", lines, "words", words, "characters", chars, "bytes", bytes);
    }

    private void check(Path p) throws IOException {
        if (!Files.isRegularFile(p)) throw new IllegalArgumentException("not a file: " + p);
        if (Files.size(p) > props.getMaxFileBytes())
            throw new IOException("file too big (raise app.skills.fileinspect.max-file-bytes)");
    }
}
