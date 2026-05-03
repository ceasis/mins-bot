package com.minsbot.skills.filefind;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class FileFindService {
    private final FileFindConfig.FileFindProperties props;
    public FileFindService(FileFindConfig.FileFindProperties props) { this.props = props; }

    public Map<String, Object> find(String basePath, String glob, String regex, boolean recursive, Integer limit) throws IOException {
        Path base = resolve(basePath);
        if (!Files.isDirectory(base)) throw new IllegalArgumentException("not a directory: " + base);
        int cap = Math.min(limit == null ? props.getMaxResults() : limit, props.getMaxResults());

        PathMatcher globMatcher = (glob == null || glob.isBlank()) ? null
                : FileSystems.getDefault().getPathMatcher("glob:" + glob);
        Pattern regexPattern = (regex == null || regex.isBlank()) ? null : Pattern.compile(regex);

        List<Map<String, Object>> matches = new ArrayList<>();
        int depth = recursive ? 16 : 1;
        Files.walkFileTree(base, EnumSet.noneOf(FileVisitOption.class), depth, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                if (matches.size() >= cap) return FileVisitResult.TERMINATE;
                String name = f.getFileName().toString();
                boolean match = (globMatcher == null && regexPattern == null);
                if (globMatcher != null && globMatcher.matches(f.getFileName())) match = true;
                if (regexPattern != null && regexPattern.matcher(name).find()) match = true;
                if (match) {
                    matches.add(Map.of(
                            "path", f.toAbsolutePath().toString(),
                            "name", name,
                            "sizeBytes", a.size(),
                            "modified", a.lastModifiedTime().toString()));
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path f, IOException e) { return FileVisitResult.CONTINUE; }
        });

        return Map.of("basePath", base.toAbsolutePath().toString(),
                "glob", glob == null ? "" : glob,
                "regex", regex == null ? "" : regex,
                "recursive", recursive,
                "matchCount", matches.size(),
                "matches", matches);
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
