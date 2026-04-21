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
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/setup")
public class SetupWizardController {

    private static final Logger log = LoggerFactory.getLogger(SetupWizardController.class);
    private static final String OLLAMA_API = "http://localhost:11434";

    private final FirstRunService firstRun;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public SetupWizardController(FirstRunService firstRun) {
        this.firstRun = firstRun;
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

    /** Curated catalog of Ollama-servable models grouped by category. */
    @GetMapping(value = "/catalog", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> catalog() {
        return ResponseEntity.ok(ModelCatalog.curated());
    }
}
