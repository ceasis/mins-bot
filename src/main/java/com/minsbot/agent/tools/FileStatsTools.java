package com.minsbot.agent.tools;

import com.minsbot.skills.filestats.FileStatsConfig;
import com.minsbot.skills.filestats.FileStatsService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Lets the chat agent answer "how many files in folder X", "files by type in
 * Downloads", etc. — directly, without telling the user to run PowerShell.
 */
@Component
public class FileStatsTools {

    @Autowired(required = false) private FileStatsService svc;
    @Autowired(required = false) private FileStatsConfig.FileStatsProperties props;
    @Autowired(required = false) private ToolExecutionNotifier notifier;

    @Tool(description = "Count files in a folder grouped by extension. Use when the user says "
            + "'how many files in my downloads folder', 'count files by type in X', 'what's in "
            + "my desktop folder'. Accepts shortcuts: 'Downloads', 'Desktop', 'Documents', '~', "
            + "'~/...', or absolute paths. Returns total count + per-extension breakdown.")
    public String countFiles(
            @ToolParam(description = "Folder path or shortcut: 'Downloads', 'Desktop', '~/projects', or absolute path") String path,
            @ToolParam(description = "Recurse into subfolders. Default false (count only top-level).", required = false) Boolean recursive,
            @ToolParam(description = "Include dotfiles (.gitignore etc). Default false.", required = false) Boolean includeDotfiles) {
        if (svc == null || props == null) return "filestats skill not loaded.";
        if (!props.isEnabled()) return "filestats skill is disabled. Set app.skills.filestats.enabled=true.";
        if (notifier != null) notifier.notify("📁 counting files in " + path + "...");
        try {
            Map<String, Object> r = svc.countByExtension(path,
                    recursive != null && recursive,
                    includeDotfiles != null && includeDotfiles);
            StringBuilder sb = new StringBuilder();
            sb.append("📁 ").append(r.get("path")).append("\n");
            sb.append("Total: ").append(r.get("totalFiles")).append(" files · ")
                    .append(r.get("totalMb")).append(" MB");
            Object subs = r.get("subdirectories");
            if (subs instanceof Number n && n.longValue() > 0)
                sb.append(" · ").append(n).append(" subfolders");
            sb.append("\n\n");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> byExt = (List<Map<String, Object>>) r.get("byExtension");
            if (byExt.isEmpty()) sb.append("(empty)");
            else {
                sb.append("By type:\n");
                int n = Math.min(20, byExt.size());
                for (int i = 0; i < n; i++) {
                    Map<String, Object> e = byExt.get(i);
                    sb.append("  .").append(e.get("extension"))
                            .append(" — ").append(e.get("count"))
                            .append(" files · ").append(e.get("totalMb")).append(" MB\n");
                }
                if (byExt.size() > n) sb.append("  ... and ").append(byExt.size() - n).append(" more types");
            }
            return sb.toString();
        } catch (Exception e) {
            return "Failed: " + e.getMessage();
        }
    }
}
