package com.minsbot;

import com.minsbot.agent.tools.PluginLoaderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * REST controller for managing skill plugins via the web UI.
 * Provides upload, load/unload, and delete operations for JAR plugins.
 */
@RestController
@RequestMapping("/api/skills")
public class SkillsController {

    private static final Logger log = LoggerFactory.getLogger(SkillsController.class);

    private final PluginLoaderService pluginLoader;

    public SkillsController(PluginLoaderService pluginLoader) {
        this.pluginLoader = pluginLoader;
    }

    @GetMapping("/plugins")
    public List<Map<String, Object>> listPlugins() {
        return pluginLoader.getPluginList();
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadPlugin(
            @RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No file provided"));
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.toLowerCase().endsWith(".jar")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Only .jar files are accepted"));
        }

        // Sanitize: strip path separators, keep only the filename
        String safeName = originalName.replace("\\", "/");
        safeName = safeName.substring(safeName.lastIndexOf('/') + 1);
        safeName = safeName.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (!safeName.toLowerCase().endsWith(".jar")) safeName += ".jar";

        try {
            Path pluginsDir = pluginLoader.getPluginsDir();
            Files.createDirectories(pluginsDir);
            Path target = pluginsDir.resolve(safeName);
            file.transferTo(target.toFile());
            log.info("[Skills] Uploaded: {} ({} bytes)", safeName, file.getSize());

            // Auto-load the plugin
            String loadResult = pluginLoader.loadPlugin(safeName);

            return ResponseEntity.ok(Map.of(
                    "name", safeName,
                    "size", file.getSize(),
                    "loadResult", loadResult
            ));
        } catch (Exception e) {
            log.error("[Skills] Upload failed: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Upload failed: " + e.getMessage()));
        }
    }

    @PostMapping("/{name}/load")
    public Map<String, String> loadPlugin(@PathVariable String name) {
        String result = pluginLoader.loadPlugin(name);
        return Map.of("result", result);
    }

    @PostMapping("/{name}/unload")
    public Map<String, String> unloadPlugin(@PathVariable String name) {
        String result = pluginLoader.unloadPlugin(name);
        return Map.of("result", result);
    }

    @DeleteMapping("/{name}")
    public Map<String, String> deletePlugin(@PathVariable String name) {
        String result = pluginLoader.deletePlugin(name);
        return Map.of("result", result);
    }

    // ─── Published skills ────────────────────────────────────────────────

    @GetMapping("/published")
    public List<Map<String, Object>> listPublished() {
        return pluginLoader.getPublishedSkills();
    }

    @PostMapping("/publish")
    public ResponseEntity<Map<String, Object>> publishSkill(
            @RequestParam("file") MultipartFile file,
            @RequestParam("author") String author,
            @RequestParam(value = "description", defaultValue = "") String description) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No file provided"));
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.toLowerCase().endsWith(".java")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Only .java files are accepted"));
        }
        if (author == null || author.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Author name is required"));
        }
        try {
            String result = pluginLoader.publishSkill(
                    author.trim(), description.trim(),
                    originalName, file.getBytes());
            return ResponseEntity.ok(Map.of("result", result));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Publish failed: " + e.getMessage()));
        }
    }

    @DeleteMapping("/published/{name}")
    public Map<String, String> deletePublished(@PathVariable String name) {
        String result = pluginLoader.deletePublishedSkill(name);
        return Map.of("result", result);
    }
}
