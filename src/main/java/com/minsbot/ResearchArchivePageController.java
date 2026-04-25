package com.minsbot;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
 * Browse the research archive. `/research.html` lists every archived
 * synthesis (first line = query title, timestamped); `/research/{id}`
 * renders the full markdown for one result.
 */
@RestController
public class ResearchArchivePageController {

    private static final Path DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "research_archive");
    private static final DateTimeFormatter FS_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter HUMAN_TS = DateTimeFormatter.ofPattern("EEE, MMM d · HH:mm");

    @GetMapping(value = {"/research.html", "/research"}, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> index() {
        List<Entry> entries = loadAll();
        StringBuilder sb = new StringBuilder();
        startHtml(sb, "Research archive");
        sb.append("<h1>Mins Bot — Research archive</h1>");
        sb.append("<div class=sub>").append(entries.size()).append(" saved synthesis result(s). ")
          .append("Ask the bot to research anything and it lands here automatically.</div>");
        if (entries.isEmpty()) {
            sb.append("<div class=empty>No research saved yet.</div>");
        } else {
            sb.append("<input id=q placeholder='Filter by query or content…' autofocus>");
            for (Entry e : entries) {
                sb.append("<a class=note data-note data-body=\"").append(esc(e.body.toLowerCase())).append("\" ")
                  .append("href=\"/research/").append(esc(e.id)).append("\">");
                sb.append("<div class=t>").append(esc(e.humanTime)).append("</div>");
                sb.append("<div class=b>").append(esc(e.title)).append("</div>");
                sb.append("</a>");
            }
            sb.append("<script>const q=document.getElementById('q');")
              .append("q.addEventListener('input',()=>{const v=q.value.trim().toLowerCase();")
              .append("document.querySelectorAll('[data-note]').forEach(el=>{")
              .append("el.classList.toggle('hide',v!==''&&el.dataset.body.indexOf(v)<0);});});</script>");
        }
        sb.append("</body></html>");
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(sb.toString());
    }

    @GetMapping(value = "/research/{id}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> one(@PathVariable String id) {
        String safe = id.replaceAll("[^A-Za-z0-9\\-]", "");
        Path p = DIR.resolve(safe + ".md").normalize();
        if (!p.startsWith(DIR) || !Files.isRegularFile(p)) {
            return ResponseEntity.status(404).body("<h1>Not found</h1>");
        }
        String body;
        try { body = Files.readString(p, StandardCharsets.UTF_8); }
        catch (IOException e) { return ResponseEntity.status(500).body("<h1>Read error</h1>"); }
        StringBuilder sb = new StringBuilder();
        startHtml(sb, "Research — " + safe);
        sb.append("<a href='/research.html' class=back>← back to archive</a>");
        sb.append("<pre class=body>").append(esc(body)).append("</pre>");
        sb.append("</body></html>");
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(sb.toString());
    }

    private static void startHtml(StringBuilder sb, String title) {
        sb.append("<!doctype html><html><head><meta charset=utf-8><title>").append(esc(title)).append("</title>");
        sb.append("<meta name=viewport content='width=device-width, initial-scale=1'><style>");
        sb.append("body{margin:0;font:14px/1.6 ui-sans-serif,system-ui,-apple-system,Segoe UI,Roboto,sans-serif;");
        sb.append("background:#0f1115;color:#e6e8ef;padding:24px}");
        sb.append("h1{margin:0 0 4px;font-size:22px;font-weight:600;letter-spacing:-0.01em}");
        sb.append(".sub{color:#8a93a6;font-size:13px;margin-bottom:16px}");
        sb.append("input{width:100%;max-width:700px;background:#1f232c;color:#e6e8ef;");
        sb.append("border:1px solid #2a2f3a;border-radius:8px;padding:10px 14px;font-size:14px;font-family:inherit;margin-bottom:18px}");
        sb.append("input:focus{outline:none;border-color:#7c5cff}");
        sb.append(".note{display:block;padding:12px 14px;border:1px solid #222733;border-radius:8px;");
        sb.append("margin-bottom:10px;background:#171a21;text-decoration:none;color:inherit}");
        sb.append(".note:hover{border-color:#7c5cff}");
        sb.append(".note .t{color:#7c5cff;font-size:12px;font-family:'JetBrains Mono',Consolas,monospace;margin-bottom:4px}");
        sb.append(".note .b{color:#d9dce3}");
        sb.append(".empty{color:#8a93a6;font-style:italic;padding:20px 0}");
        sb.append(".hide{display:none}");
        sb.append(".back{display:inline-block;color:#7c5cff;text-decoration:none;margin-bottom:12px}");
        sb.append(".body{white-space:pre-wrap;word-break:break-word;background:#171a21;");
        sb.append("border:1px solid #222733;border-radius:8px;padding:16px;color:#d9dce3;font:13px/1.6 ui-sans-serif,system-ui,-apple-system,Segoe UI,Roboto,sans-serif}");
        sb.append("</style></head><body>");
    }

    private static List<Entry> loadAll() {
        List<Entry> out = new ArrayList<>();
        if (!Files.isDirectory(DIR)) return out;
        try (Stream<Path> s = Files.list(DIR)) {
            s.filter(p -> p.toString().endsWith(".md"))
             .sorted(Comparator.reverseOrder())
             .forEach(p -> {
                 try {
                     String body = Files.readString(p, StandardCharsets.UTF_8);
                     String id = p.getFileName().toString().replace(".md", "");
                     String title = body.split("\\R", 2)[0].replace("#", "").trim();
                     if (title.isEmpty()) title = id;
                     String human;
                     try { human = LocalDateTime.parse(id, FS_TS).format(HUMAN_TS); }
                     catch (Exception e) { human = id; }
                     out.add(new Entry(id, human, title, body));
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

    private record Entry(String id, String humanTime, String title, String body) {}
}
