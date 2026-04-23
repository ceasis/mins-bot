package com.minsbot.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
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
import java.util.Objects;

/**
 * Anthropic Claude Opus 4.6 Vision API for UI element coordinate detection.
 * Uses the standard Messages API with image input.
 */
@Component
public class ClaudeVisionService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeVisionService.class);

    @Value("${app.anthropic.api-key:}")
    private String apiKeyFromProps;

    private final Environment environment;

    @Value("${app.claude.model:claude-opus-4-6}")
    private String model;

    private String apiKey;
    private HttpClient httpClient;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ModuleStatsService moduleStats;

    public ClaudeVisionService(Environment environment) {
        this.environment = environment;
    }

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";

    @PostConstruct
    void init() {
        apiKey = (apiKeyFromProps != null && !apiKeyFromProps.isBlank())
                ? apiKeyFromProps
                : Objects.requireNonNullElse(environment.getProperty("ANTHROPIC_API_KEY"), "");
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[Claude] No API key — Claude Vision disabled. " +
                    "Set app.anthropic.api-key in application-secrets.properties (project root) or env var ANTHROPIC_API_KEY. " +
                    "Resolved value is {} (null={}, blank={})",
                    apiKey == null ? "null" : (apiKey.isEmpty() ? "''" : "'" + apiKey.substring(0, Math.min(apiKey.length(), 6)) + "...'"),
                    apiKey == null, apiKey != null && apiKey.isBlank());
        } else {
            log.info("[Claude] Ready (model={}, key={}...)", model, apiKey.substring(0, Math.min(apiKey.length(), 10)));
        }
    }

    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank() && httpClient != null;
    }

    /**
     * Find a UI element's center coordinates in an image.
     * Returns int[] {x, y} or null if not found.
     */
    @com.minsbot.offline.RequiresOnline("Claude Vision coordinate detection")
    public int[] findElementCoordinates(Path imagePath, String elementDescription,
                                         int imgWidth, int imgHeight) {
        if (!isAvailable() || imagePath == null || !Files.exists(imagePath)) return null;

        try {
            byte[] imageBytes = Files.readAllBytes(imagePath);
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            String mediaType = imagePath.toString().toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";

            String prompt = "You are a pixel-precise UI element locator. "
                    + "Find the element with the text '" + elementDescription + "' in this screenshot and return "
                    + "the CENTER coordinates of that text.\n\n"
                    + "CRITICAL RULES:\n"
                    + "- Return the CENTER of the TEXT itself, not the icon next to it, not nearby elements.\n"
                    + "- The coordinates must land ON the text '" + elementDescription + "'.\n"
                    + "- If there are multiple '" + elementDescription + "' elements, pick the one that looks like a clickable "
                    + "button, tab, or link (not a static label or heading).\n"
                    + "- Image dimensions: " + imgWidth + "x" + imgHeight + " pixels.\n"
                    + "- Return ONLY one line: COORDS:x,y (integers, center of the text)\n"
                    + "- If not found, return: NOT_FOUND";

            String requestBody = buildRequest(base64, mediaType, prompt);

            log.info("[Claude] findElement — image: {} ({} bytes), {}x{}, model={}, looking for: '{}'",
                    imagePath.getFileName(), imageBytes.length, imgWidth, imgHeight, model, elementDescription);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", API_VERSION)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("[Claude] API returned HTTP {}: {}", response.statusCode(),
                        truncate(response.body(), 500));
                return null;
            }

            log.info("[Claude] HTTP body (first 500 chars): {}", truncate(response.body(), 500));
            String content = extractContent(response.body());
            log.info("[Claude] parsed content: '{}'", truncate(content, 200));

            if (content == null || content.isBlank() || content.contains("NOT_FOUND")) {
                log.info("[Claude] '{}' not found", elementDescription);
                return null;
            }

            // Parse COORDS:x,y
            for (String line : content.trim().split("\\r?\\n")) {
                line = line.trim();
                if (line.startsWith("COORDS:")) {
                    String[] parts = line.substring(7).split(",");
                    if (parts.length == 2) {
                        int x = Integer.parseInt(parts[0].trim());
                        int y = Integer.parseInt(parts[1].trim());

                        // Claude vision models often return coordinates in 0-1000 normalized scale.
                        // Detect and rescale to actual pixel dimensions.
                        if (x <= 1000 && y <= 1000 && (imgWidth > 1000 || imgHeight > 1000)) {
                            int scaledX = (int) Math.round(x * imgWidth / 1000.0);
                            int scaledY = (int) Math.round(y * imgHeight / 1000.0);
                            log.info("[Claude] Rescaled normalized ({},{}) → ({},{}) for {}x{}",
                                    x, y, scaledX, scaledY, imgWidth, imgHeight);
                            x = scaledX;
                            y = scaledY;
                        }

                        if (x >= 0 && x <= imgWidth && y >= 0 && y <= imgHeight) {
                            if (moduleStats != null) moduleStats.recordVisionCall("Claude " + model);
                            log.info("[Claude] RESULT: '{}' at ({},{}) model={}", elementDescription, x, y, model);
                            return new int[]{x, y};
                        }
                        log.warn("[Claude] coords out of bounds: ({},{}) for {}x{}", x, y, imgWidth, imgHeight);
                    }
                }
            }
            return null;

        } catch (Exception e) {
            log.warn("[Claude] findElement failed: {}", e.getMessage());
            return null;
        }
    }

    private String buildRequest(String base64Image, String mediaType, String prompt) {
        return "{\"model\":\"" + escapeJson(model) + "\",\"max_tokens\":256,\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"" + mediaType + "\",\"data\":\"" + base64Image + "\"}},{\"type\":\"text\",\"text\":\"" + escapeJson(prompt) + "\"}]}]}";
    }

    /** Extract text content from Anthropic Messages API response. */
    private static String extractContent(String json) {
        // Response: {"content":[{"type":"text","text":"COORDS:x,y"}],...}
        // Find "type":"text" to anchor, then find the "text":"..." value after it.
        int contentIdx = json.indexOf("\"content\"");
        if (contentIdx < 0) {
            log.warn("[Claude] extractContent — no 'content' field in response");
            return "";
        }

        int typeText = json.indexOf("\"type\":\"text\"", contentIdx);
        if (typeText < 0) {
            log.warn("[Claude] extractContent — no type:text in response");
            return "";
        }

        // Find "text": after the "type":"text" marker
        int textIdx = json.indexOf("\"text\":", typeText + 12);
        if (textIdx < 0) {
            log.warn("[Claude] extractContent — no text: key after type:text");
            return "";
        }

        int start = json.indexOf('"', textIdx + 6);
        if (start < 0) return "";
        start++;

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
        String result = sb.toString().trim();
        if (result.isEmpty()) {
            log.warn("[Claude] extractContent — parsed empty. Raw (200 chars): {}", truncate(json, 200));
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
