package com.minsbot.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Tertiary code generator. Same "ask for a JSON file manifest, then write the
 * files in Java" shape as {@link SpecialCodeGenerator}, but driven by a locally
 * installed model via Ollama's /api/chat endpoint. Runs fully offline once a
 * coder-capable model is pulled (e.g. qwen2.5-coder:7b, deepseek-coder:6.7b).
 */
@Component
public class LocalCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(LocalCodeGenerator.class);

    private final ToolExecutionNotifier notifier;
    private final ProjectBootstrapService bootstrap;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Value("${app.ollama.url:http://localhost:11434}")
    private String ollamaUrl;

    @Value("${app.local-code.model:qwen2.5-coder:7b}")
    private String defaultModel;

    public LocalCodeGenerator(ToolExecutionNotifier notifier, ProjectBootstrapService bootstrap) {
        this.notifier = notifier;
        this.bootstrap = bootstrap;
    }

    @Tool(description = "LOCAL code generator. Use when the user says 'local' (e.g. 'create me a local "
            + "java project for X') or explicitly asks to scaffold offline / with a local model. "
            + "Calls a locally installed Ollama model (default: qwen2.5-coder:7b) to produce a JSON file "
            + "manifest, then writes the files into the working directory. No API key, no internet.")
    public String createCodeLocal(
            @ToolParam(description = "Plain-English coding task") String task,
            @ToolParam(description = "Absolute working directory, created if missing") String workingDir) {
        return run(task, workingDir, null, true, false);
    }

    /** Direct invocation used by CodeController. If {@code model} is null, uses the configured default. */
    public String run(String task, String workingDir, String model, boolean createGithub, boolean isPrivate) {
        return runWithSink(task, workingDir, model, createGithub, isPrivate, CodeGenSink.NOOP);
    }

    /** Variant that emits progress into {@link CodeGenSink} for SSE streaming / job records. */
    public String runWithSink(String task, String workingDir, String model,
                              boolean createGithub, boolean isPrivate, CodeGenSink sink) {
        String useModel = (model == null || model.isBlank()) ? defaultModel : model.trim();
        notifier.notify("Local generator (" + useModel + "): " + abbreviate(task, 60) + "...");
        try {
            if (task == null || task.isBlank()) return "Error: task is required.";
            if (workingDir == null || workingDir.isBlank()) return "Error: workingDir is required.";

            Path dir = Path.of(workingDir);
            Files.createDirectories(dir);

            String systemPrompt =
                    "You are a code scaffolding assistant. The user will describe a project. " +
                    "Respond with a SINGLE JSON object and nothing else — no prose, no markdown fences. " +
                    "Schema: {\"files\":[{\"path\":\"relative/path.ext\",\"content\":\"full file contents\"}], " +
                    "\"summary\":\"one-line description\"}. " +
                    "Keep paths relative. Include every file needed to run the project. " +
                    "No binaries. Keep under ~20 files.";

            String body = mapper.writeValueAsString(Map.of(
                    "model", useModel,
                    "stream", false,
                    "format", "json",
                    "messages", new Object[]{
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", task)
                    }
            ));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaUrl.replaceAll("/+$", "") + "/api/chat"))
                    .header("content-type", "application/json")
                    .timeout(Duration.ofMinutes(5))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            notifier.notify("Calling Ollama (" + useModel + ")...");
            sink.log("Calling Ollama model=" + useModel + "...");
            log.info("[LocalCodeGen] Calling Ollama model={} for task: {}", useModel, abbreviate(task, 100));
            HttpResponse<String> resp;
            try {
                resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            } catch (java.net.ConnectException e) {
                return "Ollama is not running at " + ollamaUrl + ". Start it with: ollama serve";
            }
            notifier.notify("Got response, parsing manifest...");
            if (resp.statusCode() != 200) {
                String b = abbreviate(resp.body(), 500);
                if (resp.statusCode() == 404 && b.contains("not found")) {
                    return "Model '" + useModel + "' is not installed. Pull it with: ollama pull " + useModel;
                }
                return "Ollama error " + resp.statusCode() + ": " + b;
            }

            JsonNode root = mapper.readTree(resp.body());
            String text = root.path("message").path("content").asText("");
            if (text.isBlank()) return "Error: empty model response.";

            JsonNode manifest;
            try {
                manifest = mapper.readTree(stripFences(text));
            } catch (Exception e) {
                return "Error: model did not return valid JSON.\n\nRaw output:\n" + abbreviate(text, 1500);
            }
            JsonNode files = manifest.path("files");
            if (!files.isArray() || files.isEmpty()) {
                return "Error: manifest contained no files.\n\nRaw output:\n" + abbreviate(text, 1500);
            }

            notifier.notify("Manifest has " + files.size() + " files, writing...");
            int written = 0;
            StringBuilder fileList = new StringBuilder();
            for (JsonNode f : files) {
                String rel = f.path("path").asText("").replace('\\', '/').replaceFirst("^/+", "");
                String content = f.path("content").asText("");
                if (rel.isBlank() || rel.contains("..")) continue;
                Path target = dir.resolve(rel).normalize();
                if (!target.startsWith(dir)) continue;
                Files.createDirectories(target.getParent());
                notifier.notify("Generating " + tail(rel) + "...");
                Files.writeString(target, content, StandardCharsets.UTF_8);
                sink.file(rel);
                log.info("[LocalCodeGen] wrote {} ({} chars)", rel, content.length());
                fileList.append("  • ").append(rel).append('\n');
                written++;
            }
            notifier.notify("Wrote " + written + " files.");

            String summary = manifest.path("summary").asText("");
            String result = "[Local] model=" + useModel + " dir=" + dir + "\n"
                    + "Wrote " + written + " file(s).\n"
                    + (summary.isBlank() ? "" : "Summary: " + summary + "\n")
                    + "\nFiles:\n" + fileList;
            if (written > 0 && createGithub) {
                sink.status("pushing");
                sink.log("Initializing git and pushing to GitHub...");
                String bs = bootstrap.bootstrap(dir, isPrivate);
                result += bs;
                if (!bs.isBlank()) sink.log(bs.trim());
            }
            return result;
        } catch (Exception e) {
            log.warn("[LocalCodeGen] failed", e);
            return "Error: " + e.getMessage();
        }
    }

    private static String stripFences(String s) {
        String t = s.trim();
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            if (firstNl > 0) t = t.substring(firstNl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        return t.trim();
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String tail(String path) {
        if (path == null || path.isEmpty()) return "(file)";
        int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return i >= 0 ? path.substring(i + 1) : path;
    }
}
