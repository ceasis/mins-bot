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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Secondary "special" code generator. Unlike ClaudeCodeTools which shells out
 * to the Claude Code CLI (an agentic tool-calling loop), this one makes a
 * single direct call to the Anthropic Messages API, asks for a structured
 * JSON file manifest, and writes the files itself in Java. Intended as a
 * lightweight alternative for simple scaffolding.
 */
@Component
public class SpecialCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(SpecialCodeGenerator.class);
    private static final String API_URL = "https://api.anthropic.com/v1/messages";

    private final ToolExecutionNotifier notifier;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    @Value("${app.claude.api-key:}")
    private String apiKey;

    @Value("${app.claude.model:claude-opus-4-6}")
    private String model;

    public SpecialCodeGenerator(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @Tool(description = "SECONDARY 'special' code generator. Only use when the user says "
            + "'special' (e.g. 'create me special java project for X'). "
            + "Makes a single Anthropic API call asking for a JSON file manifest, then writes "
            + "those files into the working directory. Simpler and faster than ClaudeCodeTools "
            + "but not agentic — no shell commands, no iteration. Prefer ClaudeCodeTools by default.")
    public String createCodeSpecial(
            @ToolParam(description = "Plain-English coding task") String task,
            @ToolParam(description = "Absolute working directory, e.g. 'C:\\Users\\cholo\\code-gen\\foo'. "
                    + "Created if missing. Files are written relative to this folder.") String workingDir) {
        return run(task, workingDir);
    }

    /** Direct invocation path used by CodeController. */
    public String run(String task, String workingDir) {
        notifier.notify("Special generator: " + abbreviate(task, 60) + "...");
        try {
            if (task == null || task.isBlank()) return "Error: task is required.";
            if (workingDir == null || workingDir.isBlank()) return "Error: workingDir is required.";
            if (apiKey == null || apiKey.isBlank()) {
                return "Anthropic API key not configured. Set ANTHROPIC_API_KEY env var "
                        + "or app.anthropic.api-key in application-secrets.properties.";
            }

            Path dir = Path.of(workingDir);
            Files.createDirectories(dir);

            String systemPrompt =
                    "You are a code scaffolding assistant. The user will describe a project. " +
                    "Respond with a SINGLE JSON object and nothing else — no prose, no markdown fences. " +
                    "Schema: {\"files\":[{\"path\":\"relative/path.ext\",\"content\":\"full file contents\"}], " +
                    "\"summary\":\"one-line description of what was generated\"}. " +
                    "Keep paths relative. Include every file needed to run the project. " +
                    "Do not include binary files. Keep the total under ~30 files.";

            String body = mapper.writeValueAsString(Map.of(
                    "model", model,
                    "max_tokens", 8000,
                    "system", systemPrompt,
                    "messages", new Object[]{ Map.of("role", "user", "content", task) }
            ));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .header("content-type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .timeout(Duration.ofMinutes(3))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            log.info("[SpecialCodeGen] Calling Anthropic model={} for task: {}", model, abbreviate(task, 100));
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return "Anthropic API error " + resp.statusCode() + ": " + abbreviate(resp.body(), 500);
            }

            JsonNode root = mapper.readTree(resp.body());
            String text = root.path("content").path(0).path("text").asText("");
            if (text.isBlank()) return "Error: empty model response.";

            JsonNode manifest = mapper.readTree(stripFences(text));
            JsonNode files = manifest.path("files");
            if (!files.isArray() || files.isEmpty()) return "Error: manifest contained no files.";

            int written = 0;
            StringBuilder fileList = new StringBuilder();
            for (JsonNode f : files) {
                String rel = f.path("path").asText("").replace('\\', '/').replaceFirst("^/+", "");
                String content = f.path("content").asText("");
                if (rel.isBlank() || rel.contains("..")) continue;
                Path target = dir.resolve(rel).normalize();
                if (!target.startsWith(dir)) continue;
                Files.createDirectories(target.getParent());
                Files.writeString(target, content, StandardCharsets.UTF_8);
                fileList.append("  • ").append(rel).append('\n');
                written++;
            }

            String summary = manifest.path("summary").asText("");
            return "[Special] model=" + model + " dir=" + dir + "\n"
                    + "Wrote " + written + " file(s).\n"
                    + (summary.isBlank() ? "" : "Summary: " + summary + "\n")
                    + "\nFiles:\n" + fileList;
        } catch (Exception e) {
            log.warn("[SpecialCodeGen] failed", e);
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
}
