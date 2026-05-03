package com.minsbot.skills.filerename;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Bulk rename files matching a glob in a directory using regex find/replace
 * on the filename. Supports {date} token in replacement. dryRun=true returns
 * the planned renames without touching the filesystem.
 */
@Service
public class FileRenameService {

    public Map<String, Object> rename(String basePath, String glob, String regex,
                                      String replacement, boolean dryRun) throws IOException {
        if (basePath == null || basePath.isBlank()) throw new IllegalArgumentException("basePath required");
        if (regex == null) regex = "";
        if (replacement == null) replacement = "";
        Path base = Paths.get(basePath);
        if (!Files.isDirectory(base)) throw new IllegalArgumentException("not a directory: " + basePath);
        Pattern p = Pattern.compile(regex);
        String repl = replacement.replace("{date}", LocalDate.now().toString());

        List<Map<String, Object>> changes = new ArrayList<>();
        int renamed = 0, skipped = 0, failed = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(base, glob == null || glob.isBlank() ? "*" : glob)) {
            for (Path f : ds) {
                if (!Files.isRegularFile(f)) continue;
                String oldName = f.getFileName().toString();
                String newName = p.matcher(oldName).replaceAll(repl);
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("oldName", oldName);
                c.put("newName", newName);
                if (oldName.equals(newName)) { c.put("status", "unchanged"); skipped++; changes.add(c); continue; }
                if (dryRun) { c.put("status", "would-rename"); changes.add(c); renamed++; continue; }
                try {
                    Files.move(f, f.resolveSibling(newName));
                    c.put("status", "renamed");
                    renamed++;
                } catch (Exception e) {
                    c.put("status", "failed");
                    c.put("error", e.getMessage());
                    failed++;
                }
                changes.add(c);
            }
        }
        return Map.of("basePath", base.toAbsolutePath().toString(),
                "dryRun", dryRun,
                "renamed", renamed,
                "unchanged", skipped,
                "failed", failed,
                "changes", changes);
    }
}
