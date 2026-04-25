package com.minsbot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Bundle every personal-data folder (quick_notes, research_archive,
 * briefing_history) into a single timestamped ZIP under
 * mins_bot_data/exports/. Lets the user back up or migrate everything
 * at once — local-first data portability.
 */
@Component
public class ExportBundleTool {

    private static final Logger log = LoggerFactory.getLogger(ExportBundleTool.class);
    private static final Path BASE = Paths.get(System.getProperty("user.home"), "mins_bot_data");
    private static final Path OUT_DIR = BASE.resolve("exports");
    private static final String[] INCLUDES = {"quick_notes", "research_archive", "briefing_history"};

    private final ToolExecutionNotifier notifier;

    public ExportBundleTool(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @Tool(description = "Export all personal data (notes, research archive, briefing history) into a "
            + "single timestamped ZIP. Use when the user says 'back up my data', 'export everything', "
            + "'give me my data', 'create a backup'.")
    public String exportBundle() {
        try {
            Files.createDirectories(OUT_DIR);
            String stem = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path zip = OUT_DIR.resolve("mins-bot-export-" + stem + ".zip");
            notifier.notify("📦 building export bundle...");
            int fileCount = 0;
            try (OutputStream os = Files.newOutputStream(zip);
                 ZipOutputStream zos = new ZipOutputStream(os)) {
                for (String sub : INCLUDES) {
                    Path src = BASE.resolve(sub);
                    if (!Files.isDirectory(src)) continue;
                    fileCount += addDir(zos, src, sub);
                }
                // Add a manifest
                ZipEntry e = new ZipEntry("MANIFEST.txt");
                zos.putNextEntry(e);
                String manifest = "Mins Bot export\nGenerated: " + LocalDateTime.now() + "\n"
                        + "Folders: " + String.join(", ", INCLUDES) + "\n"
                        + "File count: " + fileCount + "\n";
                zos.write(manifest.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            return "✅ Exported " + fileCount + " file(s) → " + zip;
        } catch (Exception e) {
            log.warn("[ExportBundle] failed: {}", e.getMessage(), e);
            return "Export failed: " + e.getMessage();
        }
    }

    private static int addDir(ZipOutputStream zos, Path dir, String prefix) throws IOException {
        int[] count = {0};
        Files.walk(dir).filter(Files::isRegularFile).forEach(p -> {
            try {
                String entryName = prefix + "/" + dir.relativize(p).toString().replace('\\', '/');
                ZipEntry e = new ZipEntry(entryName);
                zos.putNextEntry(e);
                Files.copy(p, zos);
                zos.closeEntry();
                count[0]++;
            } catch (IOException ignored) {}
        });
        return count[0];
    }
}
