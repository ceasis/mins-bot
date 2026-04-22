package com.minsbot.firstrun;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/setup")
public class SetupWizardController {

    private static final Logger log = LoggerFactory.getLogger(SetupWizardController.class);
    private static final String OLLAMA_API = "http://localhost:11434";

    private final FirstRunService firstRun;
    private final ComfyUiInstallerService comfyInstaller;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public SetupWizardController(FirstRunService firstRun, ComfyUiInstallerService comfyInstaller) {
        this.firstRun = firstRun;
        this.comfyInstaller = comfyInstaller;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(firstRun.status());
    }

    @PostMapping("/complete")
    public ResponseEntity<?> complete() {
        firstRun.markComplete();
        return ResponseEntity.ok(Map.of("markedComplete", true));
    }

    /** Persist the user's preferred default model. */
    @PostMapping(value = "/set-default", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> setDefault(@RequestParam String model) {
        firstRun.setActiveModel(model);
        return ResponseEntity.ok(Map.of("activeModel", firstRun.getActiveModel()));
    }

    /** Start the Ollama daemon (fire-and-forget). Returns quickly; caller should poll status. */
    @PostMapping(value = "/start-ollama-service", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> startOllamaService() {
        if (firstRun.isOllamaRunning()) {
            return ResponseEntity.ok(Map.of("alreadyRunning", true));
        }
        if (!firstRun.isOllamaInstalled()) {
            return ResponseEntity.status(400).body(Map.of("error", "Ollama is not installed"));
        }
        try {
            new ProcessBuilder("ollama", "serve").redirectErrorStream(true).start();
            // Give it a moment; client will poll status separately.
            Thread.sleep(1500);
            return ResponseEntity.ok(Map.of("started", firstRun.isOllamaRunning()));
        } catch (Exception e) {
            log.warn("[Setup] start-ollama-service failed: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Install Ollama via winget (silent). Blocks until done.
     */
    @PostMapping("/install-ollama")
    public ResponseEntity<?> installOllama() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (firstRun.isOllamaInstalled()) {
            out.put("alreadyInstalled", true);
            out.put("status", firstRun.status());
            return ResponseEntity.ok(out);
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "winget", "install", "--id", "Ollama.Ollama",
                    "--silent", "--accept-source-agreements", "--accept-package-agreements"
            ).redirectErrorStream(true);
            Process p = pb.start();
            boolean done = p.waitFor(5, TimeUnit.MINUTES);
            if (!done) {
                p.destroyForcibly();
                out.put("error", "winget install timed out after 5 minutes");
                return ResponseEntity.status(500).body(out);
            }
            int exit = p.exitValue();
            out.put("wingetExitCode", exit);
            out.put("status", firstRun.status());
            if (exit != 0 && !firstRun.isOllamaInstalled()) {
                out.put("error", "Install failed. Install manually from https://ollama.com");
                return ResponseEntity.status(500).body(out);
            }
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            log.warn("[Setup] install-ollama failed: {}", e.getMessage());
            out.put("error", e.getMessage());
            out.put("hint", "Install manually: https://ollama.com");
            return ResponseEntity.status(500).body(out);
        }
    }

    /**
     * Stream the model pull with live progress as Server-Sent Events.
     * Ollama's /api/pull returns a JSON-line stream.
     */
    @GetMapping(value = "/pull-model", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter pullModel(@RequestParam(defaultValue = FirstRunService.DEFAULT_MODEL) String model) {
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(30).toMillis());
        new Thread(() -> {
            try {
                if (!firstRun.isOllamaRunning()) {
                    try { new ProcessBuilder("ollama", "serve").redirectErrorStream(true).start(); } catch (Exception ignored) {}
                    Thread.sleep(3000);
                }
                String body = "{\"name\":\"" + model + "\",\"stream\":true}";
                HttpRequest req = HttpRequest.newBuilder(URI.create(OLLAMA_API + "/api/pull"))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofMinutes(30))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<java.io.InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        emitter.send(SseEmitter.event().name("progress").data(line));
                    }
                }
                emitter.send(SseEmitter.event().name("done").data("{\"status\":\"done\"}"));
                emitter.complete();
            } catch (Exception e) {
                try {
                    String msg = e.getMessage() == null ? "" : e.getMessage().replace("\"", "'");
                    emitter.send(SseEmitter.event().name("error").data("{\"error\":\"" + msg + "\"}"));
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
            }
        }, "ollama-pull-" + model).start();
        return emitter;
    }

    /** Proxy Ollama /api/tags — lists locally installed models with sizes. */
    @GetMapping(value = "/installed-models", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> installedModels() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(OLLAMA_API + "/api/tags"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return ResponseEntity.ok(Map.of("ollamaRunning", false, "models", new Object[0]));
            }
            return ResponseEntity.ok()
                    .header("Content-Type", "application/json")
                    .body(resp.body());
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("ollamaRunning", false, "models", new Object[0]));
        }
    }

    /** Delete a locally installed model via Ollama /api/delete. */
    @PostMapping(value = "/remove-model", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> removeModel(@RequestParam String model) {
        try {
            String body = "{\"name\":\"" + model.replace("\"", "") + "\"}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(OLLAMA_API + "/api/delete"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .method("DELETE", HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                return ResponseEntity.status(resp.statusCode())
                        .body(Map.of("error", "Ollama returned " + resp.statusCode(), "body", resp.body()));
            }
            return ResponseEntity.ok(Map.of("removed", model));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /** Curated catalog of local models. ComfyUI entries are enriched with an
     *  {@code installed} flag based on whether the checkpoint file exists on disk. */
    @GetMapping(value = "/catalog", produces = MediaType.APPLICATION_JSON_VALUE)
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> catalog() {
        Map<String, Object> cat = ModelCatalog.curated();
        java.nio.file.Path comfyRoot = comfyInstaller.root();
        List<Map<String, Object>> cats = (List<Map<String, Object>>) cat.get("categories");
        if (cats != null) {
            for (Map<String, Object> c : cats) {
                List<Map<String, Object>> models = (List<Map<String, Object>>) c.get("models");
                if (models == null) continue;
                for (Map<String, Object> m : models) {
                    if (!"comfyui".equals(m.get("backend"))) continue;
                    String folder = (String) m.get("comfyFolder");
                    String filename = (String) m.get("comfyFilename");
                    if (folder == null || filename == null) continue;
                    java.nio.file.Path f = comfyRoot.resolve("models").resolve(folder).resolve(filename);
                    m.put("installed", java.nio.file.Files.exists(f) && fileSize(f) > 1024 * 1024);
                }
            }
        }
        return ResponseEntity.ok(cat);
    }

    private long fileSize(java.nio.file.Path p) {
        try { return java.nio.file.Files.size(p); } catch (Exception e) { return 0; }
    }

    /**
     * One-click ComfyUI model installer. Looks up the model in the curated
     * catalog, checks prereqs (git, python), clones & sets up ComfyUI if
     * missing, starts the server, and downloads the checkpoint — all while
     * streaming live progress events over SSE.
     *
     * <p>Events:</p>
     * <ul>
     *   <li>{@code phase} — {"phase":"prereq|install|start|download|done","message":"..."}</li>
     *   <li>{@code log} — single-line log output from subprocesses</li>
     *   <li>{@code progress} — {"done":BYTES,"total":BYTES} for the download phase</li>
     *   <li>{@code error} — {"error":"..."} and the stream closes</li>
     * </ul>
     */
    @GetMapping(value = "/install-comfyui-model", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter installComfyUiModel(@RequestParam String tag) {
        SseEmitter emitter = new SseEmitter(Duration.ofHours(2).toMillis());

        Map<String, Object> model = findComfyModel(tag);
        if (model == null) {
            try {
                emitter.send(SseEmitter.event().name("error").data(Map.of("error", "Unknown model tag: " + tag)));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }

        new Thread(() -> {
            java.util.function.Consumer<String> emit = s -> {
                try {
                    int sep = s.indexOf('|');
                    String event = (sep > 0) ? s.substring(0, sep) : "log";
                    String rest = (sep > 0) ? s.substring(sep + 1) : s;
                    if ("progress".equals(event)) {
                        int sep2 = rest.indexOf('|');
                        String done = sep2 > 0 ? rest.substring(0, sep2) : rest;
                        String total = sep2 > 0 ? rest.substring(sep2 + 1) : "-1";
                        emitter.send(SseEmitter.event().name("progress")
                                .data("{\"done\":" + done + ",\"total\":" + total + "}"));
                    } else if ("phase".equals(event)) {
                        int sep2 = rest.indexOf('|');
                        String phase = sep2 > 0 ? rest.substring(0, sep2) : rest;
                        String msg = sep2 > 0 ? rest.substring(sep2 + 1) : "";
                        String escMsg = msg.replace("\\", "\\\\").replace("\"", "\\\"");
                        emitter.send(SseEmitter.event().name("phase")
                                .data("{\"phase\":\"" + phase + "\",\"message\":\"" + escMsg + "\"}"));
                    } else {
                        String escaped = rest.replace("\\", "\\\\").replace("\"", "\\\"");
                        emitter.send(SseEmitter.event().name(event)
                                .data("{\"message\":\"" + escaped + "\"}"));
                    }
                } catch (Exception ignored) {}
            };

            try {
                // 1. Prereq check
                emit.accept("phase|prereq|Checking git & Python…");
                String prereqErr = comfyInstaller.prereqError();
                if (prereqErr != null) {
                    String esc = prereqErr.replace("\\", "\\\\").replace("\"", "\\\"");
                    emitter.send(SseEmitter.event().name("error").data("{\"error\":\"" + esc + "\",\"prereq\":true}"));
                    emitter.complete();
                    return;
                }

                // 2. Install ComfyUI if not present. Either way, verify CUDA torch
                // so a previously-botched install (CPU-only wheel) can self-heal.
                if (!comfyInstaller.isInstalled()) {
                    emit.accept("phase|install|Installing ComfyUI (one-time, ~2 GB download)…");
                    comfyInstaller.installComfyUi(emit);
                } else {
                    emit.accept("phase|install|ComfyUI present — verifying PyTorch CUDA build…");
                    emit.accept("log|ComfyUI already installed at " + comfyInstaller.root());
                    comfyInstaller.ensureCudaTorch(emit);
                }

                // 3. Start ComfyUI if not running
                if (!comfyInstaller.isRunning()) {
                    emit.accept("phase|start|Starting ComfyUI server…");
                    boolean started = comfyInstaller.startComfyUi(emit);
                    if (!started) {
                        String tail = tailComfyLog(comfyInstaller, 15);
                        for (String line : tail.split("\\R")) {
                            if (!line.isBlank()) emit.accept("log|[comfyui.log] " + line);
                        }
                        String err = "ComfyUI failed to start. Most likely cause: pip install didn't finish "
                                + "(missing PyTorch) or CUDA/driver mismatch. Log: "
                                + comfyInstaller.root().resolve("comfyui.log").toString().replace("\\", "/");
                        String esc = err.replace("\\", "\\\\").replace("\"", "\\\"");
                        emitter.send(SseEmitter.event().name("error").data("{\"error\":\"" + esc + "\"}"));
                        emitter.complete();
                        return;
                    }
                } else {
                    emit.accept("log|ComfyUI already running on localhost:8188");
                }

                // 4. Download the model file
                String folder = (String) model.get("comfyFolder");
                String filename = (String) model.get("comfyFilename");
                String url = (String) model.get("comfyDownloadUrl");
                emit.accept("phase|download|Downloading " + filename + "…");
                comfyInstaller.downloadModel(folder, filename, url, emit);

                emit.accept("phase|done|Model ready — ask the bot to generate an image.");
                emitter.send(SseEmitter.event().name("done").data("{\"status\":\"done\"}"));
                emitter.complete();
            } catch (Exception e) {
                log.warn("[Setup] install-comfyui-model failed: {}", e.getMessage(), e);
                try {
                    String msg = e.getMessage() == null ? "unknown error" : e.getMessage();
                    String esc = msg.replace("\\", "\\\\").replace("\"", "\\\"");
                    emitter.send(SseEmitter.event().name("error").data("{\"error\":\"" + esc + "\"}"));
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
            }
        }, "comfyui-install-" + tag).start();

        return emitter;
    }

    /** Read the last {@code n} lines of ComfyUI's stdout/stderr log. Empty string on any failure. */
    private String tailComfyLog(ComfyUiInstallerService installer, int n) {
        try {
            java.nio.file.Path logPath = installer.root().resolve("comfyui.log");
            if (!java.nio.file.Files.exists(logPath)) return "";
            java.util.List<String> lines = java.nio.file.Files.readAllLines(logPath, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - n);
            return String.join("\n", lines.subList(from, lines.size()));
        } catch (Exception e) {
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findComfyModel(String tag) {
        Map<String, Object> cat = ModelCatalog.curated();
        Object cats = cat.get("categories");
        if (!(cats instanceof Iterable)) return null;
        for (Object c : (Iterable<?>) cats) {
            if (!(c instanceof Map)) continue;
            Object ms = ((Map<String, Object>) c).get("models");
            if (!(ms instanceof Iterable)) continue;
            for (Object m : (Iterable<?>) ms) {
                if (!(m instanceof Map)) continue;
                Map<String, Object> mm = (Map<String, Object>) m;
                if (tag.equals(mm.get("tag")) && "comfyui".equals(mm.get("backend"))) return mm;
            }
        }
        return null;
    }
}
