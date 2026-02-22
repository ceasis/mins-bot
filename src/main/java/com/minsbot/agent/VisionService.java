package com.minsbot.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

/**
 * Analyzes screenshots using the OpenAI Vision API (gpt-4o-mini by default).
 * Uses raw HttpClient (not Spring AI ChatClient) to avoid polluting conversation memory.
 *
 * <p>Called by {@link ScreenMemoryService#captureNow()} for live screen captures.
 * Falls back gracefully (returns null) if API key is missing, network fails, or timeout.</p>
 */
@Component
public class VisionService {

    private static final Logger log = LoggerFactory.getLogger(VisionService.class);

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url:https://api.openai.com}")
    private String baseUrl;

    @Value("${app.vision.model:gpt-4o-mini}")
    private String model;

    @Value("${app.vision.detail:high}")
    private String detail;

    private HttpClient httpClient;

    private static final String SYSTEM_PROMPT = """
            You are a screen reader. Analyze this screenshot and provide:
            1. ALL visible text (preserve formatting, code structure, paragraphs)
            2. The application(s) visible (browser, IDE, terminal, chat, etc.)
            3. What the user appears to be doing (coding, reading, browsing, chatting, etc.)
            4. Any notable UI elements (dialogs, notifications, errors, popups)

            Prioritize extracting complete text content. Be thorough and accurate.
            Do NOT add commentary — just describe what you see.""";

    @PostConstruct
    void init() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        if (apiKey == null || apiKey.isBlank()) {
            log.info("[Vision] No API key — Vision analysis disabled");
        } else {
            log.info("[Vision] Ready (model={}, detail={})", model, detail);
        }
    }

    /** True if this service can make vision API calls. */
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank() && httpClient != null;
    }

    /**
     * Analyze a screenshot using the OpenAI Vision API.
     *
     * @param imagePath path to a PNG screenshot file
     * @return the AI description/text, or null on failure
     */
    public String analyzeScreenshot(Path imagePath) {
        if (!isAvailable()) return null;
        if (imagePath == null || !Files.exists(imagePath)) return null;

        try {
            // 1. Read and base64-encode the image
            byte[] imageBytes = Files.readAllBytes(imagePath);
            String base64 = Base64.getEncoder().encodeToString(imageBytes);

            // 2. Build the JSON request body
            String requestBody = buildVisionRequest(base64);

            // 3. POST to OpenAI chat/completions
            String url = baseUrl.endsWith("/")
                    ? baseUrl + "v1/chat/completions"
                    : baseUrl + "/v1/chat/completions";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.debug("[Vision] API returned HTTP {}: {}", response.statusCode(),
                        truncate(response.body(), 200));
                return null;
            }

            String content = extractContent(response.body());
            if (content == null || content.isBlank()) {
                log.debug("[Vision] Empty response from API");
                return null;
            }

            log.debug("[Vision] Analysis complete ({} chars)", content.length());
            return content;

        } catch (Exception e) {
            log.debug("[Vision] Analysis failed: {}", e.getMessage());
            return null;
        }
    }

    // ═══ JSON helpers (manual — same pattern as ToolClassifierService) ═══

    private String buildVisionRequest(String base64Image) {
        // Multi-content message format for Vision API
        // The system prompt is sent as the text part alongside the image in a single user message
        return "{\"model\":\"%s\",\"max_tokens\":1000,\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"%s\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,%s\",\"detail\":\"%s\"}}]}]}"
                .formatted(
                        escapeJson(model),
                        escapeJson(SYSTEM_PROMPT),
                        base64Image,  // base64 is already JSON-safe (alphanumeric + /+=)
                        escapeJson(detail)
                );
    }

    /** Extract the assistant content from the OpenAI chat completions response. */
    private static String extractContent(String json) {
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
