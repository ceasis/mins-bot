package com.minsbot;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

/**
 * Programmatic access to the research archive — symmetric with /api/notes.
 * GET list, GET one, DELETE one. No write endpoint here: archive entries
 * are produced by ResearchTool and ArchiveUrlTool, not direct user input.
 */
@RestController
@RequestMapping("/api/research")
public class ResearchApiController {

    private static final Path DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "research_archive");
    private static final Path TRASH =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "research_archive_trash");
    private static final DateTimeFormatter FS_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter HUMAN_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> list() {
        List<Map<String, String>> items = new ArrayList<>();
        if (Files.isDirectory(DIR)) {
            try (Stream<Path> s = Files.list(DIR)) {
                s.filter(p -> p.toString().endsWith(".md"))
                 .sorted(Comparator.reverseOrder())
                 .forEach(p -> {
                     try {
                         String body = Files.readString(p, StandardCharsets.UTF_8);
                         String id = p.getFileName().toString().replace(".md", "");
                         String title = body.split("\\R", 2)[0].replace("#", "").trim();
                         String time;
                         try { time = LocalDateTime.parse(id, FS_TS).format(HUMAN_TS); }
                         catch (Exception e) { time = id; }
                         items.add(Map.of("id", id, "time", time, "title", title));
                     } catch (IOException ignored) {}
                 });
            } catch (IOException ignored) {}
        }
        return ResponseEntity.ok(Map.of("count", items.size(), "items", items));
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> one(@PathVariable String id) {
        String safe = id.replaceAll("[^A-Za-z0-9\\-]", "");
        Path p = DIR.resolve(safe + ".md").normalize();
        if (!p.startsWith(DIR) || !Files.isRegularFile(p)) {
            return ResponseEntity.status(404).body(Map.of("ok", false, "error", "not found"));
        }
        try {
            String body = Files.readString(p, StandardCharsets.UTF_8);
            return ResponseEntity.ok(Map.of("ok", true, "id", safe, "body", body));
        } catch (IOException e) {
            return ResponseEntity.status(500).body(Map.of("ok", false, "error", e.getMessage()));
        }
    }

    @DeleteMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String id) {
        String safe = id.replaceAll("[^A-Za-z0-9\\-]", "");
        Path p = DIR.resolve(safe + ".md").normalize();
        if (!p.startsWith(DIR) || !Files.isRegularFile(p)) {
            return ResponseEntity.status(404).body(Map.of("ok", false, "error", "not found"));
        }
        try {
            Files.createDirectories(TRASH);
            Files.move(p, TRASH.resolve(safe + ".md"), StandardCopyOption.REPLACE_EXISTING);
            return ResponseEntity.ok(Map.of("ok", true, "result", "Moved to trash"));
        } catch (IOException e) {
            return ResponseEntity.status(500).body(Map.of("ok", false, "error", e.getMessage()));
        }
    }
}
