package com.minsbot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Lightweight AI-based tool category classifier.
 * Uses gpt-4o-mini via raw HTTP (not Spring AI ChatClient) to avoid
 * polluting conversation memory or interfering with the main model config.
 *
 * <p>Called by {@link ToolRouter} as a fallback when regex keyword matching
 * doesn't find any category match — handles natural language variations
 * that rigid keywords miss.</p>
 */
@Component
public class ToolClassifierService {

    private static final Logger log = LoggerFactory.getLogger(ToolClassifierService.class);

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url:https://api.openai.com}")
    private String baseUrl;

    @Value("${app.tool-classifier.model:gpt-4o-mini}")
    private String model;

    private HttpClient httpClient;

    /** All valid category names (must match ToolRouter category names exactly). */
    private static final Set<String> VALID_CATEGORIES = Set.of(
            "chat_browser", "browser", "sites", "files", "system", "media",
            "ai_model", "communication", "scheduling", "utility", "export",
            "plugins", "hotkeys", "tray", "screen_memory", "audio_memory",
            "playlist"
    );

    private static final String SYSTEM_PROMPT = """
            You are a tool router. Given a user message, return which tool categories are needed.
            Categories:
            - chat_browser: browsing in the built-in chat browser
            - browser: web search, URLs, opening websites, tabs
            - sites: login credentials, saved sites, passwords
            - files: file/folder operations, disk, zip, read/write files
            - system: apps, windows, processes, screenshots, mouse/keyboard control, volume, shutdown, click on element, find button, click button
            - media: images, photos, PDF, text-to-speech, voice
            - ai_model: ollama, model switching, summarization, huggingface
            - communication: email, weather
            - scheduling: reminders, timers, alarms, cron, recurring tasks
            - utility: calculator, math, QR codes, hashing, unit conversion
            - export: export chat history
            - plugins: load/unload jar plugins
            - hotkeys: keyboard shortcuts
            - tray: system tray
            - screen_memory: what's on screen, what was I doing/watching, OCR, screen capture
            - audio_memory: what's playing, what do you hear, system audio, music, record audio, capture audio
            - playlist: detected songs, music playlist, what songs have I listened to, add/remove song from playlist

            Return at most 3 of the most relevant category names, comma-separated. If none match, return: none""";

    @PostConstruct
    void init() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        if (apiKey == null || apiKey.isBlank()) {
            log.info("[ToolClassifier] No API key — AI classification disabled");
        } else {
            log.info("[ToolClassifier] Ready (model={}, baseUrl={})", model, baseUrl);
        }
    }

    /** True if this service can make classification calls. */
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank() && httpClient != null;
    }

    /**
     * Classify a user message into tool category names.
     *
     * @return list of category name strings, or empty list on failure/timeout
     */
    public List<String> classify(String userMessage) {
        if (!isAvailable() || userMessage == null || userMessage.isBlank()) {
            return Collections.emptyList();
        }

        try {
            String requestBody = buildRequestJson(userMessage);

            String url = baseUrl.endsWith("/")
                    ? baseUrl + "v1/chat/completions"
                    : baseUrl + "/v1/chat/completions";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(3))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.debug("[ToolClassifier] API returned HTTP {}", response.statusCode());
                return Collections.emptyList();
            }

            String content = extractContent(response.body());
            List<String> categories = parseCategories(content);
            log.debug("[ToolClassifier] '{}' → {}", truncate(userMessage, 40), categories);
            return categories;

        } catch (Exception e) {
            log.debug("[ToolClassifier] Classification failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ═══ JSON helpers (manual to avoid extra dependencies) ═══

    private String buildRequestJson(String userMessage) {
        return """
                {"model":"%s","temperature":0,"max_tokens":60,"messages":[{"role":"system","content":"%s"},{"role":"user","content":"%s"}]}"""
                .formatted(
                        escapeJson(model),
                        escapeJson(SYSTEM_PROMPT),
                        escapeJson(userMessage)
                );
    }

    /** Extract the assistant content from the OpenAI chat completions response. */
    private static String extractContent(String json) {
        // Find "content":"..." in the response — simple pattern for this predictable JSON
        int idx = json.indexOf("\"content\":");
        // Skip past system/user messages to find the assistant's content
        // The response has choices[0].message.content
        int choicesIdx = json.indexOf("\"choices\"");
        if (choicesIdx < 0) return "";
        int contentIdx = json.indexOf("\"content\":", choicesIdx);
        if (contentIdx < 0) return "";

        int start = json.indexOf('"', contentIdx + 10);
        if (start < 0) return "";
        start++; // skip opening quote

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') break;
            if (c == '\\' && i + 1 < json.length()) {
                i++;
                char next = json.charAt(i);
                if (next == 'n') sb.append('\n');
                else if (next == 't') sb.append('\t');
                else sb.append(next);
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    /** Parse comma-separated category names, filtering to valid ones only. Max 3 results. */
    private static final int MAX_CATEGORIES = 3;

    private static List<String> parseCategories(String content) {
        if (content == null || content.isBlank() || content.equalsIgnoreCase("none")) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (String part : content.split("[,\\s]+")) {
            String name = part.trim().toLowerCase();
            if (VALID_CATEGORIES.contains(name)) {
                result.add(name);
                if (result.size() >= MAX_CATEGORIES) break;
            }
        }
        return result;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String truncate(String s, int max) {
        return (s != null && s.length() > max) ? s.substring(0, max) + "..." : s;
    }
}
