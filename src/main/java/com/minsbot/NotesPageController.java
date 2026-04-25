package com.minsbot;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Read-only viewer for quick-notes captured via QuickNotesTool. The bot's
 * second-brain made tangible — type to filter, see the date/time, scan at a
 * glance. No backend mutation; creation/deletion stays in the tool path.
 */
@RestController
public class NotesPageController {

    private static final Path NOTES_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "quick_notes");
    private static final DateTimeFormatter FS_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter HUMAN_TS = DateTimeFormatter.ofPattern("EEE, MMM d · HH:mm");

    @GetMapping(value = {"/notes.html", "/notes"}, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page() {
        List<Note> notes = loadNotes();
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html><head><meta charset=utf-8>");
        sb.append("<title>Mins Bot — Notes</title>");
        sb.append("<meta name=viewport content='width=device-width, initial-scale=1'>");
        sb.append("<style>");
        sb.append("body{margin:0;font:14px/1.6 ui-sans-serif,system-ui,-apple-system,Segoe UI,Roboto,sans-serif;");
        sb.append("background:#0f1115;color:#e6e8ef;padding:24px}");
        sb.append("h1{margin:0 0 4px;font-size:22px;font-weight:600;letter-spacing:-0.01em}");
        sb.append(".sub{color:#8a93a6;font-size:13px;margin-bottom:16px}");
        sb.append("input{width:100%;max-width:700px;background:#1f232c;color:#e6e8ef;");
        sb.append("border:1px solid #2a2f3a;border-radius:8px;padding:10px 14px;font:14px inherit;margin-bottom:18px}");
        sb.append("input:focus{outline:none;border-color:#7c5cff}");
        sb.append(".note{padding:12px 14px;border:1px solid #222733;border-radius:8px;margin-bottom:10px;background:#171a21}");
        sb.append(".note .t{color:#7c5cff;font-size:12px;font-family:'JetBrains Mono',Consolas,monospace;margin-bottom:4px}");
        sb.append(".note .b{color:#d9dce3;white-space:pre-wrap;word-break:break-word}");
        sb.append(".empty{color:#8a93a6;font-style:italic;padding:20px 0}");
        sb.append(".hide{display:none}");
        sb.append("</style></head><body>");
        sb.append("<h1>Mins Bot — Notes</h1>");
        sb.append("<div class=sub>").append(notes.size()).append(" note(s). ");
        sb.append("Add new ones by telling the bot 'note: …' or 'remember that …'.</div>");
        if (notes.isEmpty()) {
            sb.append("<div class=empty>No notes yet. Tell the bot to jot something down.</div>");
        } else {
            sb.append("<input id=q placeholder='Filter notes…' autofocus>");
            for (Note n : notes) {
                sb.append("<div class=note data-note data-body=\"").append(esc(n.body.toLowerCase())).append("\">");
                sb.append("<div class=t>").append(esc(n.humanTime)).append("</div>");
                sb.append("<div class=b>").append(esc(n.body)).append("</div>");
                sb.append("</div>");
            }
            sb.append("<script>");
            sb.append("const q=document.getElementById('q');");
            sb.append("q.addEventListener('input',()=>{");
            sb.append("const v=q.value.trim().toLowerCase();");
            sb.append("document.querySelectorAll('[data-note]').forEach(el=>{");
            sb.append("el.classList.toggle('hide',v!==''&&el.dataset.body.indexOf(v)<0);");
            sb.append("});});");
            sb.append("</script>");
        }
        sb.append("</body></html>");
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(sb.toString());
    }

    private static List<Note> loadNotes() {
        List<Note> out = new ArrayList<>();
        if (!Files.isDirectory(NOTES_DIR)) return out;
        try (Stream<Path> s = Files.list(NOTES_DIR)) {
            s.filter(p -> p.toString().endsWith(".txt"))
             .sorted(Comparator.reverseOrder())
             .forEach(p -> {
                 try {
                     String body = Files.readString(p, StandardCharsets.UTF_8).trim();
                     String stem = p.getFileName().toString().replace(".txt", "");
                     String human;
                     try {
                         LocalDateTime t = LocalDateTime.parse(stem, FS_TS);
                         human = t.format(HUMAN_TS);
                     } catch (Exception e) { human = stem; }
                     out.add(new Note(human, body));
                 } catch (IOException ignored) {}
             });
        } catch (IOException ignored) {}
        return out;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private record Note(String humanTime, String body) {}
}
