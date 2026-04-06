package com.minsbot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * REST endpoints for the Knowledge Base tab.
 * Stores uploaded documents in ~/mins_bot_data/knowledge_base/.
 * Supported formats: .txt, .md, .json, .csv, .log, .xml, .yaml, .yml, .properties, .html, .pdf
 */
@RestController
@RequestMapping("/api/kb")
public class KnowledgeBaseController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseController.class);
    public static final Path KB_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "knowledge_base");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".txt", ".md", ".json", ".csv", ".log", ".xml", ".yaml", ".yml",
            ".properties", ".html", ".htm", ".pdf", ".java", ".py", ".js",
            ".ts", ".sql", ".sh", ".bat", ".cfg", ".ini", ".conf", ".doc", ".docx"
    );

    // ─── POST /api/kb/upload — upload one or more files ───

    @PostMapping(value = "/upload", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("files") MultipartFile[] files) {
        if (files == null || files.length == 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "No files provided"));
        }
        try {
            Files.createDirectories(KB_DIR);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Cannot create KB directory"));
        }

        List<String> saved = new ArrayList<>();
        List<String> rejected = new ArrayList<>();

        for (MultipartFile file : files) {
            if (file.isEmpty()) continue;
            String name = sanitizeName(file.getOriginalFilename());
            if (name == null) {
                rejected.add(file.getOriginalFilename() + " (invalid name)");
                continue;
            }
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')).toLowerCase() : "";
            if (!ALLOWED_EXTENSIONS.contains(ext)) {
                rejected.add(name + " (unsupported type)");
                continue;
            }
            try {
                // Avoid overwriting: append number if exists
                Path target = KB_DIR.resolve(name);
                if (Files.exists(target)) {
                    String base = name.substring(0, name.lastIndexOf('.'));
                    int n = 1;
                    while (Files.exists(target)) {
                        target = KB_DIR.resolve(base + "_" + n + ext);
                        n++;
                    }
                }
                file.transferTo(target.toFile());
                saved.add(target.getFileName().toString());
                log.info("[KB] Uploaded: {} ({} bytes)", target.getFileName(), file.getSize());
            } catch (IOException e) {
                rejected.add(name + " (" + e.getMessage() + ")");
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("saved", saved);
        if (!rejected.isEmpty()) result.put("rejected", rejected);
        return ResponseEntity.ok(result);
    }

    // ─── GET /api/kb/list — list all documents ───

    @GetMapping(value = "/list", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> items = new ArrayList<>();
        try {
            Files.createDirectories(KB_DIR);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(KB_DIR)) {
                for (Path file : stream) {
                    if (Files.isDirectory(file)) continue;
                    BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", file.getFileName().toString());
                    item.put("size", attrs.size());
                    item.put("sizeLabel", humanSize(attrs.size()));
                    item.put("modified", FMT.format(attrs.lastModifiedTime().toInstant()));
                    items.add(item);
                }
            }
        } catch (IOException e) {
            log.warn("[KB] Failed to list: {}", e.getMessage());
        }
        items.sort(Comparator.comparing(i -> String.valueOf(i.get("name"))));
        return items;
    }

    // ─── DELETE /api/kb/{name} — delete a document ───

    @DeleteMapping(value = "/{name}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> delete(@PathVariable String name) {
        String safe = sanitizeName(name);
        if (safe == null) return Map.of("success", false, "message", "Invalid name");
        try {
            Path file = KB_DIR.resolve(safe);
            if (Files.deleteIfExists(file)) {
                log.info("[KB] Deleted: {}", safe);
                return Map.of("success", true);
            }
            return Map.of("success", false, "message", "File not found");
        } catch (IOException e) {
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    // ─── GET /api/kb/read/{name} — read a document's text content ───

    @GetMapping(value = "/read/{name}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> read(@PathVariable String name) {
        String safe = sanitizeName(name);
        if (safe == null) return Map.of("error", "Invalid name");
        try {
            Path file = KB_DIR.resolve(safe);
            if (!Files.exists(file)) return Map.of("error", "File not found");
            String content = Files.readString(file);
            // Truncate very large files for display
            if (content.length() > 50000) {
                content = content.substring(0, 50000) + "\n... (truncated, " + content.length() + " chars total)";
            }
            return Map.of("name", safe, "content", content);
        } catch (IOException e) {
            return Map.of("error", e.getMessage());
        }
    }

    // ─── Helpers ───

    private static String sanitizeName(String name) {
        if (name == null || name.isBlank()) return null;
        // Strip path separators
        String safe = name.replace("\\", "/");
        safe = safe.substring(safe.lastIndexOf('/') + 1);
        // Block path traversal
        if (safe.contains("..") || safe.startsWith(".")) return null;
        // Allow only safe characters
        safe = safe.replaceAll("[^a-zA-Z0-9._\\- ]", "_");
        return safe.isBlank() ? null : safe;
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
