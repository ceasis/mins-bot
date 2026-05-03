package com.minsbot.skills.filegrep;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class FileGrepService {
    private final FileGrepConfig.FileGrepProperties props;
    public FileGrepService(FileGrepConfig.FileGrepProperties props) { this.props = props; }

    public Map<String, Object> grep(String basePath, String pattern, String glob,
                                    boolean recursive, boolean caseInsensitive) throws IOException {
        if (pattern == null || pattern.isBlank()) throw new IllegalArgumentException("pattern required");
        Path base = resolve(basePath);
        Pattern re = Pattern.compile(pattern, caseInsensitive ? Pattern.CASE_INSENSITIVE : 0);
        PathMatcher gm = (glob == null || glob.isBlank()) ? null
                : FileSystems.getDefault().getPathMatcher("glob:" + glob);

        List<Map<String, Object>> hits = new ArrayList<>();
        int[] filesScanned = {0};
        int[] totalMatches = {0};

        if (Files.isRegularFile(base)) {
            grepFile(base, re, hits, totalMatches);
            filesScanned[0] = 1;
        } else if (Files.isDirectory(base)) {
            int depth = recursive ? 16 : 1;
            Files.walkFileTree(base, EnumSet.noneOf(FileVisitOption.class), depth, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path f, java.nio.file.attribute.BasicFileAttributes a) {
                    if (totalMatches[0] >= props.getMaxMatches()) return FileVisitResult.TERMINATE;
                    if (gm != null && !gm.matches(f.getFileName())) return FileVisitResult.CONTINUE;
                    if (a.size() > props.getMaxFileBytes()) return FileVisitResult.CONTINUE;
                    filesScanned[0]++;
                    try { grepFile(f, re, hits, totalMatches); } catch (IOException ignored) {}
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path f, IOException e) { return FileVisitResult.CONTINUE; }
            });
        } else throw new IllegalArgumentException("not a file or directory: " + base);

        return Map.of("basePath", base.toAbsolutePath().toString(),
                "pattern", pattern, "glob", glob == null ? "" : glob,
                "filesScanned", filesScanned[0],
                "matchCount", hits.size(),
                "matches", hits);
    }

    private void grepFile(Path f, Pattern re, List<Map<String, Object>> hits, int[] total) throws IOException {
        try (BufferedReader r = Files.newBufferedReader(f)) {
            String line; int lineNum = 0;
            while ((line = r.readLine()) != null) {
                lineNum++;
                Matcher m = re.matcher(line);
                if (m.find() && total[0] < props.getMaxMatches()) {
                    hits.add(Map.of(
                            "file", f.toAbsolutePath().toString(),
                            "line", lineNum,
                            "text", line.length() > 300 ? line.substring(0, 300) + "..." : line));
                    total[0]++;
                }
            }
        } catch (Exception ignored) { /* binary files etc */ }
    }

    static Path resolve(String input) {
        if (input == null || input.isBlank()) input = "~";
        String home = System.getProperty("user.home");
        String s = input.trim();
        if (s.equals("~") || s.startsWith("~/") || s.startsWith("~\\")) s = home + s.substring(1);
        s = s.replace("${HOME}", home).replace("$HOME", home);
        String low = s.toLowerCase(Locale.ROOT);
        if (List.of("downloads", "desktop", "documents", "pictures", "videos", "music").contains(low))
            s = home + java.io.File.separator + Character.toUpperCase(low.charAt(0)) + low.substring(1);
        return Paths.get(s);
    }
}
