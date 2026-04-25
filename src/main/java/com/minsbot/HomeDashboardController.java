package com.minsbot;

import com.minsbot.agent.tools.ProjectHistoryService;
import com.minsbot.agent.tools.TodaysFocusTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Front door at /home.html. Pulls together everything the user might want
 * at a glance: today's date, recent notes, recent research, quick links to
 * /tools.html / /notes.html / /research.html. No backend mutation; pure
 * read-only dashboard composed from existing on-disk artifacts.
 */
@RestController
public class HomeDashboardController {

    private static final Path NOTES_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "quick_notes");
    private static final Path RESEARCH_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "research_archive");
    private static final DateTimeFormatter FS_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter HUMAN_TS = DateTimeFormatter.ofPattern("MMM d · HH:mm");

    @Autowired(required = false) private TodaysFocusTool todaysFocusTool;
    @Autowired(required = false) private ProjectHistoryService projectHistory;
    @Autowired(required = false) private com.minsbot.agent.tools.CalendarTools calendarTools;

    private static final Path SCHEDULED_REPORTS_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "scheduled_reports");

    @GetMapping(value = "/api/home/state", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> state() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("notes_count", countFiles(NOTES_DIR, ".txt"));
        out.put("research_count", countFiles(RESEARCH_DIR, ".md"));
        List<Map<String, String>> recentNotes = new java.util.ArrayList<>();
        for (Snippet s : recentNotes(5)) {
            recentNotes.add(Map.of("id", s.id, "time", s.time, "text", s.text));
        }
        out.put("recent_notes", recentNotes);
        List<Map<String, String>> recentResearch = new java.util.ArrayList<>();
        for (Snippet s : recentResearch(5)) {
            recentResearch.add(Map.of("id", s.id, "time", s.time, "text", s.text));
        }
        out.put("recent_research", recentResearch);
        out.put("date", LocalDate.now().toString());
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(out);
    }

    private static int countPendingReminders() {
        if (!Files.isDirectory(SCHEDULED_REPORTS_DIR)) return 0;
        try (Stream<Path> s = Files.list(SCHEDULED_REPORTS_DIR)) {
            return (int) s.filter(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".json") || n.endsWith(".yml") || n.endsWith(".yaml");
            }).count();
        } catch (IOException e) { return 0; }
    }

    private static int countFiles(Path dir, String ext) {
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> s = Files.list(dir)) {
            return (int) s.filter(p -> p.toString().endsWith(ext)).count();
        } catch (IOException e) { return 0; }
    }

    @GetMapping(value = "/api/home/focus", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> focus() {
        String text;
        try {
            text = todaysFocusTool != null ? todaysFocusTool.todaysFocus() : "(focus tool unavailable)";
        } catch (Exception e) {
            text = "(focus unavailable: " + e.getMessage() + ")";
        }
        return ResponseEntity.ok(Map.of("text", text));
    }

    @GetMapping(value = {"/home.html", "/home"}, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page() {
        StringBuilder sb = new StringBuilder();
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d"));
        sb.append("<!doctype html><html><head><meta charset=utf-8><title>Mins Bot — Home</title>");
        sb.append("<meta name=viewport content='width=device-width, initial-scale=1'><style>");
        sb.append("body{margin:0;font:14px/1.6 ui-sans-serif,system-ui,-apple-system,Segoe UI,Roboto,sans-serif;");
        sb.append("background:#0f1115;color:#e6e8ef;padding:24px;max-width:1100px;margin:0 auto}");
        sb.append("h1{margin:0 0 4px;font-size:26px;font-weight:600;letter-spacing:-0.01em}");
        sb.append(".sub{color:#8a93a6;font-size:13px;margin-bottom:24px}");
        sb.append(".grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(320px,1fr));gap:16px}");
        sb.append(".card{background:#171a21;border:1px solid #222733;border-radius:10px;padding:16px}");
        sb.append(".card h2{margin:0 0 10px;font-size:13px;text-transform:uppercase;letter-spacing:0.08em;color:#8a93a6}");
        sb.append(".item{padding:8px 0;border-bottom:1px solid #1f232c}");
        sb.append(".item:last-child{border-bottom:none}");
        sb.append(".item .t{color:#7c5cff;font-size:11px;font-family:'JetBrains Mono',Consolas,monospace}");
        sb.append(".item .b{color:#d9dce3;font-size:13px;margin-top:2px}");
        sb.append(".item a{color:inherit;text-decoration:none;display:block}");
        sb.append(".item a:hover .b{color:#fff}");
        sb.append(".empty{color:#8a93a6;font-style:italic;padding:8px 0}");
        sb.append(".links{display:flex;flex-wrap:wrap;gap:8px;margin-top:8px}");
        sb.append(".chip{background:#1f232c;color:#d9dce3;border:1px solid #2a2f3a;border-radius:999px;");
        sb.append("padding:6px 12px;font-size:12px;text-decoration:none}");
        sb.append(".chip:hover{border-color:#7c5cff;color:#fff}");
        sb.append("</style></head><body>");
        sb.append("<h1>Welcome back</h1>");
        sb.append("<div class=sub>").append(today).append("</div>");

        // Focus banner — async-loaded so the page renders fast
        sb.append("<div id=focus class='card' style='margin-bottom:16px;border-color:#3a2f6a;background:#1a1729'>");
        sb.append("<h2 style='color:#a99dff'>Focus</h2>");
        sb.append("<div class=item><div class=b id=focus-text>Loading…</div></div>");
        sb.append("</div>");

        sb.append("<div class=grid>");

        // Recent notes card
        sb.append("<div class=card><h2>Recent notes</h2>");
        List<Snippet> notes = recentNotes(5);
        if (notes.isEmpty()) sb.append("<div class=empty>No notes yet — tell the bot 'note: …'.</div>");
        else for (Snippet n : notes) {
            sb.append("<div class=item><div class=t>").append(esc(n.time)).append("</div>")
              .append("<div class=b>").append(esc(n.text)).append("</div></div>");
        }
        sb.append("<div class=links><a class=chip href='/notes.html'>All notes →</a></div>");
        sb.append("</div>");

        // Recent research card
        sb.append("<div class=card><h2>Recent research</h2>");
        List<Snippet> research = recentResearch(5);
        if (research.isEmpty()) sb.append("<div class=empty>No research yet — ask the bot to research a topic.</div>");
        else for (Snippet r : research) {
            sb.append("<div class=item><a href='/research/").append(esc(r.id)).append("'>")
              .append("<div class=t>").append(esc(r.time)).append("</div>")
              .append("<div class=b>").append(esc(r.text)).append("</div></a></div>");
        }
        sb.append("<div class=links><a class=chip href='/research.html'>All research →</a></div>");
        sb.append("</div>");

        // Today's calendar card
        sb.append("<div class=card><h2>Today</h2>");
        String todayEvents = "";
        if (calendarTools != null) {
            try { todayEvents = calendarTools.getTodayEvents(); } catch (Exception ignored) {}
        }
        if (todayEvents == null || todayEvents.isBlank()) {
            sb.append("<div class=empty>No calendar events today (or calendar not connected).</div>");
        } else {
            String[] lines = todayEvents.split("\\R");
            int shown = 0;
            for (String line : lines) {
                if (line.isBlank()) continue;
                if (shown++ >= 6) break;
                sb.append("<div class=item><div class=b>").append(esc(line.trim())).append("</div></div>");
            }
        }
        int pendingReminders = countPendingReminders();
        if (pendingReminders > 0) {
            sb.append("<div class=item><div class=t>⏰ Reminders</div>")
              .append("<div class=b>").append(pendingReminders).append(" scheduled</div></div>");
        }
        sb.append("</div>");

        // Recent code projects card
        sb.append("<div class=card><h2>Recent code projects</h2>");
        java.util.List<ProjectHistoryService.ProjectRecord> projects = java.util.List.of();
        if (projectHistory != null) {
            try { projects = projectHistory.list(); } catch (Exception ignored) {}
        }
        if (projects.isEmpty()) {
            sb.append("<div class=empty>No projects yet — generate one from the Code page.</div>");
        } else {
            int shown = 0;
            for (ProjectHistoryService.ProjectRecord r : projects) {
                if (shown++ >= 5) break;
                String name = r.projectName != null ? r.projectName : (r.jobId != null ? r.jobId : "(unnamed)");
                String when = r.completedAt != null ? r.completedAt : "";
                String status = r.status != null ? r.status : "";
                sb.append("<div class=item><div class=t>").append(esc(when))
                  .append(status.isEmpty() ? "" : " · " + esc(status))
                  .append("</div><div class=b>").append(esc(name)).append("</div></div>");
            }
        }
        sb.append("</div>");

        // Quick links card
        sb.append("<div class=card><h2>Explore</h2>");
        sb.append("<div class=item><div class=b>The bot can do a lot. Browse what's available, or ask it directly.</div></div>");
        sb.append("<div class=item><div class=t style='color:#8a93a6'>To chat:</div>");
        sb.append("<div class=b style='font-size:12px;color:#8a93a6'>Use the floating Mins Bot on your desktop — the browser surface is read-only.</div></div>");
        sb.append("<div class=links>");
        sb.append("<a class=chip href='/tools.html'>🛠  All tools</a>");
        sb.append("<a class=chip href='/notes.html'>📝 Notes</a>");
        sb.append("<a class=chip href='/research.html'>🔎 Research</a>");
        sb.append("</div></div>");

        sb.append("</div>");

        // Async focus loader: hits /api/home/focus which calls TodaysFocusTool.
        sb.append("<script>(async()=>{try{");
        sb.append("const r=await fetch('/api/home/focus',{cache:'no-store'});");
        sb.append("const j=await r.json();");
        sb.append("document.getElementById('focus-text').textContent=j.text||'(no suggestion yet)';");
        sb.append("}catch(e){document.getElementById('focus-text').textContent='(focus unavailable)';}");
        sb.append("})();</script>");

        sb.append("</body></html>");
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(sb.toString());
    }

    private static List<Snippet> recentNotes(int max) {
        List<Snippet> out = new ArrayList<>();
        if (!Files.isDirectory(NOTES_DIR)) return out;
        try (Stream<Path> s = Files.list(NOTES_DIR)) {
            s.filter(p -> p.toString().endsWith(".txt"))
             .sorted(Comparator.reverseOrder())
             .limit(max)
             .forEach(p -> {
                 try {
                     String body = Files.readString(p, StandardCharsets.UTF_8).trim();
                     int nl = body.indexOf('\n');
                     String line = nl < 0 ? body : body.substring(0, nl);
                     if (line.length() > 100) line = line.substring(0, 100) + "…";
                     String stem = p.getFileName().toString().replace(".txt", "");
                     String time;
                     try { time = LocalDateTime.parse(stem, FS_TS).format(HUMAN_TS); }
                     catch (Exception e) { time = stem; }
                     out.add(new Snippet(stem, time, line));
                 } catch (IOException ignored) {}
             });
        } catch (IOException ignored) {}
        return out;
    }

    private static List<Snippet> recentResearch(int max) {
        List<Snippet> out = new ArrayList<>();
        if (!Files.isDirectory(RESEARCH_DIR)) return out;
        try (Stream<Path> s = Files.list(RESEARCH_DIR)) {
            s.filter(p -> p.toString().endsWith(".md"))
             .sorted(Comparator.reverseOrder())
             .limit(max)
             .forEach(p -> {
                 try {
                     String body = Files.readString(p, StandardCharsets.UTF_8);
                     String title = body.split("\\R", 2)[0].replace("#", "").trim();
                     if (title.length() > 100) title = title.substring(0, 100) + "…";
                     String stem = p.getFileName().toString().replace(".md", "");
                     String time;
                     try { time = LocalDateTime.parse(stem, FS_TS).format(HUMAN_TS); }
                     catch (Exception e) { time = stem; }
                     out.add(new Snippet(stem, time, title));
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

    private record Snippet(String id, String time, String text) {}
}
