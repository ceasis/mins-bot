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
 * Anthropic Claude Opus 4.6 Vision — Computer Use beta API for UI element coordinate detection.
 * Uses the computer_20251124 tool with anthropic-beta: computer-use-2025-11-24 header
 * for pixel-accurate coordinate detection.
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

    public ClaudeVisionService(Environment environment) {
        this.environment = environment;
    }

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";
    private static final String COMPUTER_USE_BETA = "computer-use-2025-11-24";

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
     * Find a UI element's center coordinates using Claude Computer Use beta API.
     * Returns int[] {x, y} or null if not found.
     */
    public int[] findElementCoordinates(Path imagePath, String elementDescription,
                                         int imgWidth, int imgHeight) {
        if (!isAvailable() || imagePath == null || !Files.exists(imagePath)) return null;

        try {
            byte[] imageBytes = Files.readAllBytes(imagePath);
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            String mediaType = imagePath.toString().toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";

            // Build Computer Use request with screenshot + click instruction
            String requestBody = buildComputerUseRequest(base64, mediaType, elementDescription, imgWidth, imgHeight);

            log.info("[Claude] findElement — image: {} ({} bytes), {}x{}, model={}, looking for: '{}'",
                    imagePath.getFileName(), imageBytes.length, imgWidth, imgHeight, model, elementDescription);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", API_VERSION)
                    .header("anthropic-beta", COMPUTER_USE_BETA)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("[Claude] API returned HTTP {}: {}", response.statusCode(),
                        truncate(response.body(), 500));
                return null;
            }

            String body = response.body();
            log.info("[Claude] HTTP body (first 600 chars): {}", truncate(body, 600));

            // Parse computer use response — look for tool_use with left_click coordinate
            int[] coords = extractClickCoordinates(body);
            if (coords != null) {
                int x = coords[0], y = coords[1];
                if (x >= 0 && x <= imgWidth && y >= 0 && y <= imgHeight) {
                    log.info("[Claude] RESULT: '{}' at ({},{}) model={}", elementDescription, x, y, model);
                    return coords;
                }
                log.warn("[Claude] coords out of bounds: ({},{}) for {}x{}", x, y, imgWidth, imgHeight);
                return null;
            }

            // Fallback: try parsing text content for COORDS:x,y format
            String content = extractTextContent(body);
            log.info("[Claude] text content fallback: '{}'", truncate(content, 200));

            if (content != null && !content.isBlank() && !content.contains("NOT_FOUND")) {
                for (String line : content.trim().split("\\r?\\n")) {
                    line = line.trim();
                    if (line.startsWith("COORDS:")) {
                        String[] parts = line.substring(7).split(",");
                        if (parts.length == 2) {
                            int x = Integer.parseInt(parts[0].trim());
                            int y = Integer.parseInt(parts[1].trim());
                            if (x >= 0 && x <= imgWidth && y >= 0 && y <= imgHeight) {
                                log.info("[Claude] RESULT (text fallback): '{}' at ({},{}) model={}",
                                        elementDescription, x, y, model);
                                return new int[]{x, y};
                            }
                        }
                    }
                }
            }

            log.info("[Claude] '{}' not found", elementDescription);
            return null;

        } catch (Exception e) {
            log.warn("[Claude] findElement failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Build a Computer Use beta request using the proper agent-loop format.
     * Simulates: user asks → Claude requests screenshot → we provide screenshot as tool_result
     * → Claude responds with left_click coordinate.
     */
    private String buildComputerUseRequest(String base64Image, String mediaType,
                                            String elementDescription, int imgWidth, int imgHeight) {
        // Proper computer use flow requires 3 messages:
        // 1. user: "Click on X"
        // 2. assistant: tool_use screenshot (simulated — Claude "requested" a screenshot)
        // 3. user: tool_result with the actual screenshot image
        // Then Claude responds with tool_use left_click at the correct coordinates.
        return "{\"model\":\"" + escapeJson(model) + "\","
                + "\"max_tokens\":1024,"
                + "\"tools\":[{"
                + "\"type\":\"computer_20251124\","
                + "\"name\":\"computer\","
                + "\"display_width_px\":" + imgWidth + ","
                + "\"display_height_px\":" + imgHeight
                + "}],"
                + "\"messages\":["
                // Turn 1: user instruction
                + "{\"role\":\"user\",\"content\":\"" + escapeJson(
                        "Find the UI element labeled '" + elementDescription
                        + "' on the screen and click on its exact center.")
                + "\"},"
                // Turn 2: assistant "requests" a screenshot (simulated)
                + "{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\","
                + "\"id\":\"toolu_screenshot_01\","
                + "\"name\":\"computer\","
                + "\"input\":{\"action\":\"screenshot\"}}]},"
                // Turn 3: we provide the screenshot as a tool_result
                + "{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\","
                + "\"tool_use_id\":\"toolu_screenshot_01\","
                + "\"content\":[{\"type\":\"image\",\"source\":"
                + "{\"type\":\"base64\",\"media_type\":\"" + mediaType + "\","
                + "\"data\":\"" + base64Image + "\"}}]}]}"
                + "]}";
    }

    /**
     * Extract click coordinates from a Computer Use tool_use response.
     * Response format: {"content":[...,{"type":"tool_use","name":"computer","input":{"action":"left_click","coordinate":[x,y]}},...]}
     */
    private static int[] extractClickCoordinates(String json) {
        // Look for "coordinate" array in tool_use response
        int coordIdx = json.indexOf("\"coordinate\"");
        if (coordIdx < 0) {
            log.info("[Claude] No 'coordinate' field in response — not a tool_use click response");
            return null;
        }

        // Find the array start [
        int bracketStart = json.indexOf('[', coordIdx + 12);
        if (bracketStart < 0) return null;

        int bracketEnd = json.indexOf(']', bracketStart);
        if (bracketEnd < 0) return null;

        String arrayContent = json.substring(bracketStart + 1, bracketEnd).trim();
        String[] parts = arrayContent.split(",");
        if (parts.length == 2) {
            try {
                int x = Integer.parseInt(parts[0].trim());
                int y = Integer.parseInt(parts[1].trim());
                log.info("[Claude] Parsed click coordinate: ({}, {})", x, y);
                return new int[]{x, y};
            } catch (NumberFormatException e) {
                log.warn("[Claude] Failed to parse coordinate values: '{}'", arrayContent);
            }
        }
        return null;
    }

    /** Extract text content from Anthropic Messages API response (fallback for non-tool responses). */
    private static String extractTextContent(String json) {
        int contentIdx = json.indexOf("\"content\"");
        if (contentIdx < 0) return "";

        int typeText = json.indexOf("\"type\":\"text\"", contentIdx);
        if (typeText < 0) return "";

        int textIdx = json.indexOf("\"text\":", typeText + 12);
        if (textIdx < 0) {
            textIdx = json.indexOf("\"text\":", typeText);
            if (textIdx < 0 || textIdx == typeText) return "";
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
