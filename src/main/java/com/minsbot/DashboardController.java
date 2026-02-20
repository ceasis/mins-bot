package com.minsbot;

import com.minsbot.agent.tools.DirectivesTools;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST controller that serves a dashboard showing directive progress,
 * gathered data stats, and autonomous mode status.
 * Accessed at /api/dashboard/data (JSON) or /dashboard (HTML page).
 */
@RestController
public class DashboardController {

    private static final Path BASE_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data");
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    @Value("${app.autonomous.enabled:false}")
    private boolean autonomousEnabled;

    /** JSON endpoint: returns dashboard data for the frontend. */
    @GetMapping("/api/dashboard/data")
    public Map<String, Object> getDashboardData() {
        Map<String, Object> data = new LinkedHashMap<>();

        // Directives
        String directives = DirectivesTools.loadDirectivesForPrompt();
        data.put("directives", directives != null ? directives : "(none)");
        data.put("autonomousEnabled", autonomousEnabled);

        // Directive folders
        List<Map<String, Object>> folders = new ArrayList<>();
        if (Files.isDirectory(BASE_DIR)) {
            try (var stream = Files.list(BASE_DIR)) {
                var dirs = stream
                        .filter(p -> Files.isDirectory(p) && p.getFileName().toString().startsWith("directive_"))
                        .sorted()
                        .collect(Collectors.toList());

                for (Path dir : dirs) {
                    Map<String, Object> info = new LinkedHashMap<>();
                    String name = dir.getFileName().toString().replace("directive_", "");
                    info.put("name", name);
                    info.put("path", dir.toAbsolutePath().toString());

                    long txtCount = countFiles(dir, ".txt");
                    long imgCount = countImages(dir);
                    long totalSize = totalSize(dir);

                    info.put("textFiles", txtCount);
                    info.put("images", imgCount);
                    info.put("totalSize", formatSize(totalSize));
                    info.put("hasSummary", Files.exists(dir.resolve("_SUMMARY.txt")));

                    // Last modified
                    try (var files = Files.list(dir)) {
                        OptionalLong lastMod = files
                                .mapToLong(p -> {
                                    try { return Files.getLastModifiedTime(p).toMillis(); }
                                    catch (IOException e) { return 0; }
                                })
                                .max();
                        info.put("lastModified", lastMod.isPresent()
                                ? FMT.format(Instant.ofEpochMilli(lastMod.getAsLong())) : "unknown");
                    }

                    folders.add(info);
                }
            } catch (IOException e) {
                data.put("error", e.getMessage());
            }
        }
        data.put("directiveFolders", folders);
        data.put("timestamp", FMT.format(Instant.now()));

        return data;
    }

    /** HTML dashboard page. */
    @GetMapping(value = "/dashboard", produces = "text/html")
    public String getDashboardHtml() {
        Map<String, Object> data = getDashboardData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> folders = (List<Map<String, Object>>) data.get("directiveFolders");

        StringBuilder html = new StringBuilder();
        html.append("""
                <!DOCTYPE html>
                <html><head><meta charset="UTF-8">
                <title>Mins Bot Dashboard</title>
                <meta http-equiv="refresh" content="30">
                <style>
                * { box-sizing: border-box; margin: 0; padding: 0; }
                body { font-family: 'Segoe UI', sans-serif; background: #0a0a1a; color: #e0e0e0; padding: 20px; }
                h1 { color: #00d4ff; margin-bottom: 5px; }
                .meta { color: #666; font-size: 0.85em; margin-bottom: 20px; }
                .section { background: #111; border: 1px solid #222; border-radius: 8px; padding: 15px; margin-bottom: 15px; }
                .section h2 { color: #00d4ff; font-size: 1.1em; margin-bottom: 10px; }
                .badge { display: inline-block; padding: 2px 8px; border-radius: 10px; font-size: 0.8em; }
                .badge-on { background: #0a3; color: #fff; }
                .badge-off { background: #555; color: #aaa; }
                .badge-summary { background: #06a; color: #fff; }
                table { width: 100%; border-collapse: collapse; }
                th, td { padding: 8px 12px; text-align: left; border-bottom: 1px solid #222; }
                th { color: #00d4ff; font-size: 0.85em; text-transform: uppercase; }
                .directives-text { white-space: pre-wrap; background: #0a0a1a; padding: 10px; border-radius: 4px;
                                   border: 1px solid #333; font-family: monospace; font-size: 0.9em; max-height: 200px; overflow-y: auto; }
                </style></head><body>
                <h1>Mins Bot Dashboard</h1>
                <p class="meta">Auto-refreshes every 30s | Last updated: %s</p>
                """.formatted(data.get("timestamp")));

        // Status
        html.append("<div class=\"section\"><h2>Status</h2>");
        html.append("<p>Autonomous Mode: <span class=\"badge ")
                .append((Boolean) data.get("autonomousEnabled") ? "badge-on\">ENABLED" : "badge-off\">DISABLED")
                .append("</span></p>");
        html.append("</div>");

        // Directives
        html.append("<div class=\"section\"><h2>Primary Directives</h2>");
        html.append("<div class=\"directives-text\">")
                .append(escapeHtml((String) data.get("directives")))
                .append("</div></div>");

        // Directive folders
        html.append("<div class=\"section\"><h2>Directive Data (")
                .append(folders.size()).append(" folders)</h2>");
        if (folders.isEmpty()) {
            html.append("<p style=\"color:#666\">No directive data gathered yet.</p>");
        } else {
            html.append("<table><tr><th>Directive</th><th>Text Files</th><th>Images</th>")
                    .append("<th>Size</th><th>Summary</th><th>Last Updated</th></tr>");
            for (Map<String, Object> f : folders) {
                html.append("<tr>")
                        .append("<td>").append(f.get("name")).append("</td>")
                        .append("<td>").append(f.get("textFiles")).append("</td>")
                        .append("<td>").append(f.get("images")).append("</td>")
                        .append("<td>").append(f.get("totalSize")).append("</td>")
                        .append("<td>").append((Boolean) f.get("hasSummary")
                                ? "<span class=\"badge badge-summary\">YES</span>" : "—").append("</td>")
                        .append("<td>").append(f.get("lastModified")).append("</td>")
                        .append("</tr>");
            }
            html.append("</table>");
        }
        html.append("</div>");

        html.append("</body></html>");
        return html.toString();
    }

    private long countFiles(Path dir, String ext) throws IOException {
        try (var s = Files.list(dir)) { return s.filter(p -> p.toString().endsWith(ext)).count(); }
    }

    private long countImages(Path dir) throws IOException {
        try (var s = Files.list(dir)) {
            return s.filter(p -> {
                String n = p.toString().toLowerCase();
                return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                        || n.endsWith(".gif") || n.endsWith(".webp");
            }).count();
        }
    }

    private long totalSize(Path dir) throws IOException {
        try (var s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile)
                    .mapToLong(p -> { try { return Files.size(p); } catch (IOException e) { return 0; } })
                    .sum();
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
