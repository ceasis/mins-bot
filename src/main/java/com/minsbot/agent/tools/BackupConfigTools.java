package com.minsbot.agent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Backup config files (.env, .config, .properties, .yml, .yaml, .json, .xml,
 * .toml, .ini, etc.) from a projects folder into a dated ZIP archive,
 * preserving the original folder structure.
 */
@Component
public class BackupConfigTools {

    private final ToolExecutionNotifier notifier;

    private static final Set<String> DEFAULT_EXTENSIONS = Set.of(
            ".env", ".config", ".properties", ".yml", ".yaml",
            ".json", ".xml", ".toml", ".ini", ".cfg", ".conf"
    );

    private static final Set<String> SKIP_DIRS = Set.of(
            "node_modules", ".git", "__pycache__", ".gradle", "build",
            "target", "dist", ".idea", ".vscode", "bin", "obj",
            "$recycle.bin", "system volume information"
    );

    public BackupConfigTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @Tool(description = "Backup important config files. Recursively scans a folder for config files "
            + "(.env, .config, .properties, .yml, .yaml, .json, .xml, .toml, .ini, .cfg, .conf), "
            + "copies them to a dated backup folder preserving folder structure, and creates a ZIP archive. "
            + "Returns a summary with total files backed up and total size.")
    public String backupConfigs(
            @ToolParam(description = "Root folder to scan for config files, e.g. C:\\Users\\me\\projects") String sourceFolder,
            @ToolParam(description = "Destination folder where the dated backup will be created, e.g. C:\\Users\\me\\backups") String backupFolder) {

        notifier.notify("Backing up config files from " + sourceFolder + "...");

        try {
            Path source = Paths.get(sourceFolder).toAbsolutePath();
            if (!Files.isDirectory(source)) return "Source folder not found: " + source;

            String dateSuffix = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            Path backupDir = Paths.get(backupFolder).toAbsolutePath()
                    .resolve("config-backup-" + dateSuffix);
            Files.createDirectories(backupDir);

            // 1. Scan and copy config files preserving structure
            List<Path> copied = new ArrayList<>();
            AtomicLong totalSize = new AtomicLong();

            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName().toString().toLowerCase();
                    if (SKIP_DIRS.contains(dirName)) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (isConfigFile(file)) {
                        Path relative = source.relativize(file);
                        Path dest = backupDir.resolve(relative);
                        try {
                            Files.createDirectories(dest.getParent());
                            Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
                            copied.add(relative);
                            totalSize.addAndGet(attrs.size());
                        } catch (IOException ignored) {}
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });

            if (copied.isEmpty()) {
                // Clean up empty backup dir
                Files.deleteIfExists(backupDir);
                return "No config files found in " + source;
            }

            // 2. Create ZIP archive
            Path zipFile = backupDir.getParent().resolve("config-backup-" + dateSuffix + ".zip");
            int zipped = 0;
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
                for (Path relative : copied) {
                    Path full = backupDir.resolve(relative);
                    String entryName = relative.toString().replace('\\', '/');
                    zos.putNextEntry(new ZipEntry(entryName));
                    Files.copy(full, zos);
                    zos.closeEntry();
                    zipped++;
                }
            }

            long zipSize = Files.size(zipFile);

            // 3. Build summary
            // Group by extension for the report
            Map<String, Integer> extCounts = new LinkedHashMap<>();
            for (Path p : copied) {
                String ext = getExtension(p.getFileName().toString());
                extCounts.merge(ext, 1, Integer::sum);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Config backup complete!\n\n");
            sb.append("Source: ").append(source).append("\n");
            sb.append("Backup folder: ").append(backupDir).append("\n");
            sb.append("ZIP archive: ").append(zipFile).append(" (").append(formatSize(zipSize)).append(")\n\n");
            sb.append("Total files: ").append(copied.size()).append("\n");
            sb.append("Total size (uncompressed): ").append(formatSize(totalSize.get())).append("\n\n");
            sb.append("Breakdown by type:\n");
            extCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(e -> sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append(" file(s)\n"));

            return sb.toString();

        } catch (IOException e) {
            return "Backup failed: " + e.getMessage();
        }
    }

    @Tool(description = "Scan a folder for config files and list them WITHOUT backing up. "
            + "Useful for previewing what would be backed up before running the actual backup.")
    public String previewConfigBackup(
            @ToolParam(description = "Root folder to scan for config files") String sourceFolder) {

        notifier.notify("Scanning for config files in " + sourceFolder + "...");

        try {
            Path source = Paths.get(sourceFolder).toAbsolutePath();
            if (!Files.isDirectory(source)) return "Folder not found: " + source;

            List<String> found = new ArrayList<>();
            AtomicLong totalSize = new AtomicLong();

            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName().toString().toLowerCase();
                    if (SKIP_DIRS.contains(dirName)) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (isConfigFile(file)) {
                        found.add(source.relativize(file).toString() + " (" + formatSize(attrs.size()) + ")");
                        totalSize.addAndGet(attrs.size());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });

            if (found.isEmpty()) return "No config files found in " + source;

            StringBuilder sb = new StringBuilder();
            sb.append("Found ").append(found.size()).append(" config file(s) totaling ")
                    .append(formatSize(totalSize.get())).append(":\n\n");

            int shown = Math.min(found.size(), 100);
            for (int i = 0; i < shown; i++) {
                sb.append("  ").append(found.get(i)).append("\n");
            }
            if (found.size() > 100) {
                sb.append("  ... and ").append(found.size() - 100).append(" more\n");
            }

            return sb.toString();

        } catch (IOException e) {
            return "Scan failed: " + e.getMessage();
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private boolean isConfigFile(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        // Match by extension
        for (String ext : DEFAULT_EXTENSIONS) {
            if (name.endsWith(ext)) return true;
        }
        // Match dotfiles that are common configs (e.g. .env, .env.local, .env.production)
        if (name.startsWith(".env")) return true;
        return false;
    }

    private String getExtension(String filename) {
        String lower = filename.toLowerCase();
        if (lower.startsWith(".env")) return ".env";
        int dot = lower.lastIndexOf('.');
        return dot >= 0 ? lower.substring(dot) : "(no ext)";
    }

    static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        double gb = mb / 1024.0;
        return String.format("%.2f GB", gb);
    }
}
