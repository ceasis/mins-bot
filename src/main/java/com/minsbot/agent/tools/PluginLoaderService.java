package com.minsbot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Plugin system that loads skill JARs from a plugins/ directory.
 * Each plugin JAR can contain @Component/@Service classes that get discovered.
 * For now, this service catalogs and loads plugins — Spring context refresh
 * would require a more complex approach, so plugins expose a simple interface.
 */
@Component
public class PluginLoaderService {

    private static final Logger log = LoggerFactory.getLogger(PluginLoaderService.class);
    private static final Path PLUGINS_DIR =
            Paths.get(System.getProperty("user.dir"), "plugins");

    private final ToolExecutionNotifier notifier;
    private final Map<String, PluginInfo> loadedPlugins = new ConcurrentHashMap<>();

    public PluginLoaderService(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @Tool(description = "List all plugin JARs found in the plugins/ directory, showing which are loaded.")
    public String listPlugins() {
        notifier.notify("Listing plugins");
        try {
            if (!Files.isDirectory(PLUGINS_DIR)) {
                Files.createDirectories(PLUGINS_DIR);
                return "Plugins directory created at " + PLUGINS_DIR.toAbsolutePath()
                        + ". No plugins found. Place .jar files there.";
            }

            List<Path> jars;
            try (var stream = Files.list(PLUGINS_DIR)) {
                jars = stream.filter(p -> p.toString().endsWith(".jar")).sorted().toList();
            }

            if (jars.isEmpty()) {
                return "No plugin JARs found in " + PLUGINS_DIR.toAbsolutePath();
            }

            StringBuilder sb = new StringBuilder("Plugins in " + PLUGINS_DIR.toAbsolutePath() + ":\n\n");
            int i = 1;
            for (Path jar : jars) {
                String name = jar.getFileName().toString();
                boolean loaded = loadedPlugins.containsKey(name);
                long size = Files.size(jar);
                sb.append(i++).append(". ").append(name)
                        .append(" (").append(formatSize(size)).append(")")
                        .append(loaded ? " [LOADED]" : " [not loaded]").append("\n");
                if (loaded) {
                    PluginInfo info = loadedPlugins.get(name);
                    sb.append("   Classes: ").append(info.classes.size()).append("\n");
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "Failed to list plugins: " + e.getMessage();
        }
    }

    @Tool(description = "Load a plugin JAR from the plugins/ directory. " +
            "Scans the JAR for classes and makes them available.")
    public String loadPlugin(
            @ToolParam(description = "The JAR filename (e.g. 'my-skill.jar')") String jarFilename) {
        notifier.notify("Loading plugin: " + jarFilename);
        try {
            Path jarPath = PLUGINS_DIR.resolve(jarFilename);
            if (!Files.exists(jarPath)) {
                return "Plugin not found: " + jarPath.toAbsolutePath();
            }

            // Scan JAR for classes
            List<String> classNames = new ArrayList<>();
            try (JarFile jar = new JarFile(jarPath.toFile())) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.getName().endsWith(".class") && !entry.getName().contains("$")) {
                        String className = entry.getName()
                                .replace('/', '.').replace(".class", "");
                        classNames.add(className);
                    }
                }
            }

            // Create a URLClassLoader for the plugin
            URLClassLoader loader = new URLClassLoader(
                    new URL[]{jarPath.toUri().toURL()},
                    getClass().getClassLoader()
            );

            PluginInfo info = new PluginInfo(jarFilename, jarPath, classNames, loader);
            loadedPlugins.put(jarFilename, info);

            log.info("[Plugin] Loaded {} — {} classes found", jarFilename, classNames.size());
            return "Plugin loaded: " + jarFilename + "\nClasses found: " + classNames.size()
                    + "\n" + String.join("\n", classNames.subList(0, Math.min(20, classNames.size())))
                    + (classNames.size() > 20 ? "\n... and " + (classNames.size() - 20) + " more" : "");
        } catch (Exception e) {
            log.error("[Plugin] Failed to load {}: {}", jarFilename, e.getMessage());
            return "Failed to load plugin: " + e.getMessage();
        }
    }

    @Tool(description = "Unload a previously loaded plugin.")
    public String unloadPlugin(
            @ToolParam(description = "The JAR filename to unload") String jarFilename) {
        notifier.notify("Unloading plugin: " + jarFilename);
        PluginInfo info = loadedPlugins.remove(jarFilename);
        if (info == null) {
            return "Plugin not loaded: " + jarFilename;
        }
        try {
            info.loader.close();
        } catch (Exception ignored) {}
        log.info("[Plugin] Unloaded: {}", jarFilename);
        return "Plugin unloaded: " + jarFilename;
    }

    // ─── Public API (used by SkillsController) ────────────────────────────

    /** Returns the plugins directory path. */
    public Path getPluginsDir() {
        return PLUGINS_DIR;
    }

    /** Returns structured plugin data for the REST API. */
    public List<Map<String, Object>> getPluginList() {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            if (!Files.isDirectory(PLUGINS_DIR)) {
                Files.createDirectories(PLUGINS_DIR);
                return result;
            }
            try (var stream = Files.list(PLUGINS_DIR)) {
                stream.filter(p -> p.toString().endsWith(".jar")).sorted().forEach(jar -> {
                    try {
                        String name = jar.getFileName().toString();
                        PluginInfo info = loadedPlugins.get(name);
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("name", name);
                        entry.put("size", Files.size(jar));
                        entry.put("sizeFormatted", formatSize(Files.size(jar)));
                        entry.put("loaded", info != null);
                        entry.put("classCount", info != null ? info.classes.size() : 0);
                        result.add(entry);
                    } catch (Exception ignored) {}
                });
            }
        } catch (Exception e) {
            log.warn("[Plugin] Failed to list plugins: {}", e.getMessage());
        }
        return result;
    }

    /** Unloads and deletes a plugin JAR from disk. */
    public String deletePlugin(String jarFilename) {
        unloadPlugin(jarFilename);
        try {
            Path jarPath = PLUGINS_DIR.resolve(jarFilename);
            if (!Files.exists(jarPath)) return "File not found: " + jarFilename;
            Files.delete(jarPath);
            log.info("[Plugin] Deleted: {}", jarFilename);
            return "Deleted: " + jarFilename;
        } catch (Exception e) {
            return "Failed to delete: " + e.getMessage();
        }
    }

    // ─── Published skills ──────────────────────────────────────────────────

    private static final Path PUBLISHED_DIR = PLUGINS_DIR.resolve("published");

    /** Returns list of published skills with metadata. */
    public List<Map<String, Object>> getPublishedSkills() {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            if (!Files.isDirectory(PUBLISHED_DIR)) return result;
            try (var stream = Files.list(PUBLISHED_DIR)) {
                stream.filter(p -> p.toString().endsWith(".java")).sorted().forEach(javaFile -> {
                    try {
                        String name = javaFile.getFileName().toString();
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("name", name);
                        entry.put("size", Files.size(javaFile));
                        entry.put("sizeFormatted", formatSize(Files.size(javaFile)));

                        // Read manifest if it exists
                        Path manifest = PUBLISHED_DIR.resolve(name.replace(".java", ".json"));
                        if (Files.exists(manifest)) {
                            String json = Files.readString(manifest);
                            entry.put("author", extractJsonField(json, "author"));
                            entry.put("description", extractJsonField(json, "description"));
                            entry.put("date", extractJsonField(json, "date"));
                        }
                        result.add(entry);
                    } catch (Exception ignored) {}
                });
            }
        } catch (Exception e) {
            log.warn("[Plugin] Failed to list published skills: {}", e.getMessage());
        }
        return result;
    }

    /** Publish a .java skill file with metadata. */
    public String publishSkill(String author, String description, String fileName, byte[] fileContent) {
        try {
            Files.createDirectories(PUBLISHED_DIR);
            String safeName = fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
            if (!safeName.toLowerCase().endsWith(".java")) safeName += ".java";

            Files.write(PUBLISHED_DIR.resolve(safeName), fileContent);

            // Write manifest
            String date = java.time.LocalDate.now().toString();
            String manifest = "{\"name\":\"" + escapeJson(safeName)
                    + "\",\"author\":\"" + escapeJson(author)
                    + "\",\"description\":\"" + escapeJson(description)
                    + "\",\"date\":\"" + date + "\"}";
            Path manifestPath = PUBLISHED_DIR.resolve(safeName.replace(".java", ".json"));
            Files.writeString(manifestPath, manifest);

            log.info("[Plugin] Published: {} by {}", safeName, author);
            return "Published: " + safeName;
        } catch (Exception e) {
            return "Failed to publish: " + e.getMessage();
        }
    }

    /** Delete a published skill and its manifest. */
    public String deletePublishedSkill(String name) {
        try {
            Path javaFile = PUBLISHED_DIR.resolve(name);
            Path manifest = PUBLISHED_DIR.resolve(name.replace(".java", ".json"));
            if (Files.exists(javaFile)) Files.delete(javaFile);
            if (Files.exists(manifest)) Files.delete(manifest);
            log.info("[Plugin] Deleted published skill: {}", name);
            return "Deleted: " + name;
        } catch (Exception e) {
            return "Failed to delete: " + e.getMessage();
        }
    }

    /** Simple JSON field extractor (for small manifest files). */
    private static String extractJsonField(String json, String field) {
        String key = "\"" + field + "\":\"";
        int start = json.indexOf(key);
        if (start < 0) return "";
        start += key.length();
        int end = json.indexOf("\"", start);
        return end > start ? json.substring(start, end) : "";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private record PluginInfo(String name, Path path, List<String> classes, URLClassLoader loader) {}
}
