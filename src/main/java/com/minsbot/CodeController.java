package com.minsbot;

import com.minsbot.agent.tools.CodeGenJob;
import com.minsbot.agent.tools.CodeGenJobService;
import com.minsbot.agent.tools.ProjectHistoryService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Backs the /code.html page.
 *
 * <p>Generation is asynchronous: {@code POST /api/code/generate} queues a job
 * and returns a {@code jobId}. The client then subscribes to
 * {@code GET /api/code/jobs/{id}/stream} via EventSource for live SSE events
 * ({@code status}, {@code log}, {@code file}, {@code result}), and can hit
 * {@code POST /api/code/jobs/{id}/cancel} at any time.</p>
 */
@RestController
@RequestMapping("/api/code")
public class CodeController {

    private final CodeGenJobService jobs;
    private final ProjectHistoryService history;

    public CodeController(CodeGenJobService jobs, ProjectHistoryService history) {
        this.jobs = jobs;
        this.history = history;
    }

    @GetMapping(value = "/history", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> history() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projects", history.list());
        return out;
    }

    /** List the most recent QA screenshot run for a project, grouped by device. */
    @GetMapping(value = "/screenshots/{project}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> listScreenshots(@org.springframework.web.bind.annotation.PathVariable String project) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            String safe = project.replaceAll("[^a-zA-Z0-9_-]", "_");
            Path root = Path.of(System.getProperty("user.home"), "mins_bot_data",
                    "qa-screenshots", safe);
            if (!Files.isDirectory(root)) { out.put("runs", List.of()); return out; }
            // Most-recent run folder = lex-latest timestamp child.
            try (var s = Files.list(root)) {
                Path latest = s.filter(Files::isDirectory)
                        .sorted(java.util.Comparator.reverseOrder())
                        .findFirst().orElse(null);
                if (latest == null) { out.put("runs", List.of()); return out; }
                // Walk: device/file.png.
                List<Map<String, Object>> items = new java.util.ArrayList<>();
                try (var ds = Files.walk(latest)) {
                    ds.filter(Files::isRegularFile)
                      .filter(p -> p.toString().endsWith(".png"))
                      .forEach(p -> {
                          Map<String, Object> m = new LinkedHashMap<>();
                          Path rel = latest.relativize(p);
                          m.put("device", rel.getName(0).toString());
                          m.put("page", rel.getName(rel.getNameCount() - 1).toString().replace(".png", ""));
                          m.put("url", "/api/code/screenshots/" + safe + "/file?path=" + p.toString().replace('\\', '/'));
                          items.add(m);
                      });
                }
                out.put("run", latest.getFileName().toString());
                out.put("runDir", latest.toString());
                out.put("items", items);
            }
        } catch (Exception e) {
            out.put("error", e.getMessage());
        }
        return out;
    }

    /** Serve a single screenshot PNG by absolute path (scoped to qa-screenshots dir). */
    @GetMapping(value = "/screenshots/{project}/file", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> screenshotFile(
            @org.springframework.web.bind.annotation.PathVariable String project,
            @org.springframework.web.bind.annotation.RequestParam String path) {
        try {
            Path safeRoot = Path.of(System.getProperty("user.home"), "mins_bot_data",
                    "qa-screenshots").toAbsolutePath().normalize();
            Path requested = Path.of(path).toAbsolutePath().normalize();
            if (!requested.startsWith(safeRoot)) return ResponseEntity.badRequest().build();
            if (!Files.exists(requested)) return ResponseEntity.notFound().build();
            byte[] body = Files.readAllBytes(requested);
            return ResponseEntity.ok().header("Cache-Control", "max-age=3600").body(body);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping(value = "/default-dir", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> defaultDir() {
        String home = System.getProperty("user.home", "");
        List<String> candidates = List.of(
                "eclipse-workspace",
                "workspace",
                "IdeaProjects",
                "Projects",
                "code",
                "dev",
                "Desktop"
        );
        String base = home + java.io.File.separator + "Desktop";
        for (String c : candidates) {
            Path p = Path.of(home, c);
            if (Files.isDirectory(p)) { base = p.toString(); break; }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("baseDir", base);
        out.put("home", home);
        return out;
    }

    @PostMapping(value = "/generate",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> generate(@RequestBody Map<String, Object> body) {
        String task = str(body, "task");
        String workingDir = str(body, "workingDir");
        String mode = strOr(body, "mode", "primary").toLowerCase();
        String model = str(body, "model");
        boolean createGithub = !"false".equalsIgnoreCase(str(body, "createGithub"));
        boolean isPrivate = "true".equalsIgnoreCase(str(body, "isPrivate"));

        if (task.isBlank())       return err("task is required");
        if (workingDir.isBlank()) return err("workingDir is required");
        if (!List.of("primary", "special", "local", "all").contains(mode))
            return err("Unknown mode: " + mode);

        CodeGenJob job = jobs.start(mode, task, workingDir, model, createGithub, isPrivate);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", job.id);
        out.put("status", job.status);
        out.put("streamUrl", "/api/code/jobs/" + job.id + "/stream");
        return out;
    }

    @GetMapping(value = "/jobs/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getJob(@PathVariable String id) {
        CodeGenJob j = jobs.get(id);
        if (j == null) return ResponseEntity.notFound().build();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", j.id);
        out.put("mode", j.mode);
        out.put("status", j.status);
        out.put("workingDir", j.workingDir);
        out.put("logs", j.snapshotLogs());
        out.put("files", j.snapshotFiles());
        out.put("startedAt", j.startedAt.toString());
        if (j.completedAt != null) out.put("completedAt", j.completedAt.toString());
        out.put("result", j.finalResult);
        return ResponseEntity.ok(out);
    }

    @GetMapping(value = "/jobs/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@PathVariable String id) {
        CodeGenJob j = jobs.get(id);
        if (j == null) return ResponseEntity.notFound().build();
        SseEmitter emitter = new SseEmitter(30L * 60 * 1000); // 30 min
        j.subscribe(emitter);
        return ResponseEntity.ok(emitter);
    }

    @PostMapping(value = "/jobs/{id}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> cancel(@PathVariable String id) {
        boolean ok = jobs.cancel(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cancelled", ok);
        return out;
    }

    // ─── helpers ───

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString();
    }

    private static String strOr(Map<String, Object> m, String k, String fallback) {
        String v = str(m, k);
        return v.isBlank() ? fallback : v;
    }

    private static Map<String, Object> err(String msg) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", msg);
        return out;
    }
}
