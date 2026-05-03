package com.minsbot.agent.tools;

import com.minsbot.skills.clipboardctl.ClipboardCtlConfig;
import com.minsbot.skills.clipboardctl.ClipboardCtlService;
import com.minsbot.skills.filediff.FileDiffConfig;
import com.minsbot.skills.filediff.FileDiffService;
import com.minsbot.skills.filefind.FileFindConfig;
import com.minsbot.skills.filefind.FileFindService;
import com.minsbot.skills.filegrep.FileGrepConfig;
import com.minsbot.skills.filegrep.FileGrepService;
import com.minsbot.skills.fileinfo.FileInfoConfig;
import com.minsbot.skills.fileinfo.FileInfoService;
import com.minsbot.skills.fileinspect.FileInspectConfig;
import com.minsbot.skills.fileinspect.FileInspectService;
import com.minsbot.skills.fileopen.FileOpenConfig;
import com.minsbot.skills.fileopen.FileOpenService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Exposes the file/text-ops skills to the chat agent so the LLM can find files,
 * grep contents, diff, peek at file head/tail, get file info, open files, and
 * read/write the clipboard — all without telling the user to run shell commands.
 */
@Component
public class FileOpsTools {

    @Autowired(required = false) private FileFindService ff;
    @Autowired(required = false) private FileFindConfig.FileFindProperties ffProps;
    @Autowired(required = false) private FileGrepService fg;
    @Autowired(required = false) private FileGrepConfig.FileGrepProperties fgProps;
    @Autowired(required = false) private FileDiffService fd;
    @Autowired(required = false) private FileDiffConfig.FileDiffProperties fdProps;
    @Autowired(required = false) private FileInspectService fi;
    @Autowired(required = false) private FileInspectConfig.FileInspectProperties fiProps;
    @Autowired(required = false) private FileInfoService finfo;
    @Autowired(required = false) private FileInfoConfig.FileInfoProperties finfoProps;
    @Autowired(required = false) private FileOpenService fo;
    @Autowired(required = false) private FileOpenConfig.FileOpenProperties foProps;
    @Autowired(required = false) private ClipboardCtlService cb;
    @Autowired(required = false) private ClipboardCtlConfig.ClipboardCtlProperties cbProps;

    @Tool(description = "Find files matching a glob pattern under a folder. Use when the user says "
            + "'find all *.pdf in Documents', 'find files named X', 'list all images in Downloads'. "
            + "Path accepts shortcuts: 'Downloads', 'Desktop', '~', '~/...', or absolute path.")
    public String findFiles(@ToolParam(description = "Folder path or shortcut") String path,
                             @ToolParam(description = "Glob pattern, e.g. '*.pdf', '**.{jpg,png}'", required = false) String glob,
                             @ToolParam(description = "Recurse into subfolders. Default true.", required = false) Boolean recursive) {
        if (ff == null || ffProps == null || !ffProps.isEnabled()) return "filefind skill is disabled.";
        try {
            Map<String, Object> r = ff.find(path, glob, null, recursive == null || recursive, null);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> matches = (List<Map<String, Object>>) r.get("matches");
            if (matches.isEmpty()) return "No files matching '" + glob + "' in " + r.get("basePath");
            StringBuilder sb = new StringBuilder("🔍 ").append(matches.size()).append(" matches in ").append(r.get("basePath")).append(":\n");
            int n = Math.min(30, matches.size());
            for (int i = 0; i < n; i++) sb.append("  ").append(matches.get(i).get("path")).append("\n");
            if (matches.size() > n) sb.append("  ... and ").append(matches.size() - n).append(" more");
            return sb.toString();
        } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "Search file contents for a string or regex (like grep). Use when the user says "
            + "'grep TODO in my project', 'find all uses of X in Y', 'search for word X in folder Z'. "
            + "Returns matching lines with file + line number.")
    public String grep(@ToolParam(description = "Folder or single file") String path,
                        @ToolParam(description = "Pattern (regex)") String pattern,
                        @ToolParam(description = "Glob to filter files, e.g. '*.java'", required = false) String glob,
                        @ToolParam(description = "Case-insensitive match. Default false.", required = false) Boolean caseInsensitive) {
        if (fg == null || fgProps == null || !fgProps.isEnabled()) return "filegrep skill is disabled.";
        try {
            Map<String, Object> r = fg.grep(path, pattern, glob, true, caseInsensitive != null && caseInsensitive);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> hits = (List<Map<String, Object>>) r.get("matches");
            if (hits.isEmpty()) return "No matches for '" + pattern + "' (scanned " + r.get("filesScanned") + " files)";
            StringBuilder sb = new StringBuilder("🔎 ").append(hits.size()).append(" hits across ").append(r.get("filesScanned")).append(" files:\n");
            int n = Math.min(30, hits.size());
            for (int i = 0; i < n; i++) {
                Map<String, Object> h = hits.get(i);
                sb.append("  ").append(h.get("file")).append(":").append(h.get("line")).append(" — ").append(h.get("text")).append("\n");
            }
            if (hits.size() > n) sb.append("  ... and ").append(hits.size() - n).append(" more");
            return sb.toString();
        } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "Diff two files (line-by-line) or two folders (set diff). Use when the user "
            + "says 'diff X and Y', 'compare two files', 'what changed between X and Y'.")
    public String diff(@ToolParam(description = "First file or folder path") String a,
                        @ToolParam(description = "Second file or folder path") String b) {
        if (fd == null || fdProps == null || !fdProps.isEnabled()) return "filediff skill is disabled.";
        try {
            java.nio.file.Path pa = java.nio.file.Paths.get(a);
            Map<String, Object> r = java.nio.file.Files.isDirectory(pa) ? fd.diffFolders(a, b) : fd.diffFiles(a, b);
            return r.toString();
        } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "Show first N lines, last N lines, or count lines/words/chars of a file. "
            + "Use when the user says 'first 20 lines of X', 'tail X', 'how many lines in X', 'wc X'.")
    public String inspectFile(@ToolParam(description = "Operation: 'head', 'tail', or 'wc'") String op,
                               @ToolParam(description = "File path") String path,
                               @ToolParam(description = "Number of lines (head/tail). Default 20.", required = false) Integer n) {
        if (fi == null || fiProps == null || !fiProps.isEnabled()) return "fileinspect skill is disabled.";
        try {
            int count = n == null ? 20 : n;
            Map<String, Object> r = switch (op.toLowerCase()) {
                case "head" -> fi.head(path, count);
                case "tail" -> fi.tail(path, count);
                case "wc", "count" -> fi.wc(path);
                default -> Map.of("error", "Unknown op: " + op);
            };
            if ("wc".equalsIgnoreCase(op) || "count".equalsIgnoreCase(op))
                return "📊 " + r.get("path") + "\n  lines: " + r.get("lines")
                        + " · words: " + r.get("words") + " · chars: " + r.get("characters") + " · bytes: " + r.get("bytes");
            return "📄 " + op + " " + count + " of " + r.get("path") + ":\n\n" + r.get("content");
        } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "Get detailed info about a file: size, modified date, mime type, owner, "
            + "optionally SHA-256 hash. Use when the user says 'info on X', 'when was X modified', "
            + "'hash this file', 'is X readable'.")
    public String fileInfo(@ToolParam(description = "File path") String path,
                            @ToolParam(description = "Compute SHA-256 hash. Default false.", required = false) Boolean withHash) {
        if (finfo == null || finfoProps == null || !finfoProps.isEnabled()) return "fileinfo skill is disabled.";
        try {
            Map<String, Object> r = finfo.info(path, withHash != null && withHash);
            StringBuilder sb = new StringBuilder("ℹ ").append(r.get("path")).append("\n");
            sb.append("  size: ").append(r.get("sizeMb")).append(" MB (").append(r.get("sizeBytes")).append(" bytes)\n");
            sb.append("  modified: ").append(r.get("modified")).append("\n");
            sb.append("  created: ").append(r.get("created")).append("\n");
            if (r.get("mimeType") != null) sb.append("  type: ").append(r.get("mimeType")).append("\n");
            if (r.get("owner") != null) sb.append("  owner: ").append(r.get("owner")).append("\n");
            if (r.get("permissions") != null) sb.append("  perms: ").append(r.get("permissions")).append("\n");
            if (r.get("sha256") != null) sb.append("  sha256: ").append(r.get("sha256"));
            return sb.toString();
        } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "Open a file with its default OS application. Use when the user says "
            + "'open file X', 'open the latest screenshot', 'open this PDF'.")
    public String openFile(@ToolParam(description = "File path") String path) {
        if (fo == null || foProps == null || !foProps.isEnabled()) return "fileopen skill is disabled.";
        try { return "📂 " + fo.openFile(path); } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "Reveal a file in the OS file explorer (Windows Explorer, macOS Finder). "
            + "Use when the user says 'show me X in Explorer', 'reveal X in Finder', 'open the folder containing X'.")
    public String revealFile(@ToolParam(description = "File path") String path) {
        if (fo == null || foProps == null || !foProps.isEnabled()) return "fileopen skill is disabled.";
        try { return "👁 " + fo.revealInExplorer(path); } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "Read what's currently on the system clipboard. Use when the user says "
            + "'what's in my clipboard', 'paste from clipboard', 'show clipboard'.")
    public String readClipboard() {
        if (cb == null || cbProps == null || !cbProps.isEnabled()) return "clipboardctl skill is disabled.";
        try {
            Map<String, Object> r = cb.read();
            int len = ((Number) r.get("length")).intValue();
            if (len == 0) return "Clipboard is empty (or holds non-text content).";
            return "📋 clipboard (" + len + " chars):\n\n" + r.get("text");
        } catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "Write text to the system clipboard. Use when the user says 'copy X to "
            + "clipboard', 'put this on my clipboard', 'copy the result'.")
    public String writeClipboard(@ToolParam(description = "Text to copy") String text) {
        if (cb == null || cbProps == null || !cbProps.isEnabled()) return "clipboardctl skill is disabled.";
        try { return "📋 copied " + cb.write(text).get("wroteLength") + " chars to clipboard"; }
        catch (Exception e) { return "Failed: " + e.getMessage(); }
    }

    @Tool(description = "Clear the system clipboard. Use when the user says 'clear clipboard', "
            + "'wipe clipboard'.")
    public String clearClipboard() {
        if (cb == null || cbProps == null || !cbProps.isEnabled()) return "clipboardctl skill is disabled.";
        cb.clear();
        return "📋 clipboard cleared";
    }
}
