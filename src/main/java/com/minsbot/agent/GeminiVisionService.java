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
 * Google Gemini Vision API integration for screenshot analysis, element location,
 * and drag verification. Uses the Gemini REST API (generativelanguage.googleapis.com).
 *
 * <p>Supports two models: a fast model (gemini-2.5-flash) for element detection and
 * a reasoning model (gemini-2.5-pro) for verification tasks.</p>
 */
@Component
public class GeminiVisionService {

    private static final Logger log = LoggerFactory.getLogger(GeminiVisionService.class);

    @Value("${app.gemini.api-key:}")
    private String apiKey;

    @Value("${app.gemini.model:gemini-2.5-flash}")
    private String model;

    @Value("${app.gemini.reasoning-model:gemini-2.5-pro}")
    private String reasoningModel;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ModuleStatsService moduleStats;

    private HttpClient httpClient;

    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/";

    @PostConstruct
    void init() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        if (apiKey == null || apiKey.isBlank()) {
            log.info("[Gemini] No API key — Gemini Vision disabled");
        } else {
            log.info("[Gemini] Ready (model={}, reasoning={})", model, reasoningModel);
        }
    }

    /** True if Gemini Vision is configured and ready. */
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank() && httpClient != null;
    }

    // ═══ Element coordinate detection ═══

    private static final String ELEMENT_FIND_PROMPT = """
            You are a precise UI element locator. Find the element described below in this screenshot \
            and return its CENTER pixel coordinates.

            RULES:
            - Return ONLY a single line in this exact format: COORDS:x,y
            - x and y are integer pixel coordinates of the CENTER of the element.
            - Coordinates must be within the image dimensions.
            - If the element is not visible or cannot be found, return exactly: NOT_FOUND
            - Do NOT add any other text, explanation, or commentary.

            Example responses:
            COORDS:450,320
            COORDS:1200,580
            NOT_FOUND""";

    /**
     * Find the pixel coordinates of a UI element using Gemini Vision.
     *
     * @param imagePath          path to the screenshot PNG
     * @param elementDescription what to find, e.g. "ANIMALS.txt", "LIVING folder"
     * @param imageWidth         image width in pixels
     * @param imageHeight        image height in pixels
     * @return int[] {x, y} or null if not found
     */
    public int[] findElementCoordinates(Path imagePath, String elementDescription,
                                         int imageWidth, int imageHeight) {
        if (!isAvailable() || imagePath == null || !Files.exists(imagePath)) return null;

        try {
            byte[] imageBytes = Files.readAllBytes(imagePath);
            String base64 = Base64.getEncoder().encodeToString(imageBytes);

            String prompt = ELEMENT_FIND_PROMPT + "\n\nFind this element: " + elementDescription
                    + "\n\nImage dimensions: " + imageWidth + "x" + imageHeight + " pixels.";

            String response = callGemini(base64, prompt, "image/png", model);
            log.info("[Gemini] findElement — looking for '{}', response: '{}'",
                    elementDescription, truncate(response, 200));

            if (response == null || response.isBlank() || response.contains("NOT_FOUND")) return null;

            String coordLine = response.trim();
            // Handle multi-line responses — find the COORDS line
            for (String line : coordLine.split("\\r?\\n")) {
                line = line.trim();
                if (line.startsWith("COORDS:")) {
                    String[] parts = line.substring(7).split(",");
                    if (parts.length == 2) {
                        int x = Integer.parseInt(parts[0].trim());
                        int y = Integer.parseInt(parts[1].trim());
                        // Gemini models often return coordinates in 0-1000 normalized scale
                        // instead of actual pixel coordinates. Detect and rescale.
                        if (x <= 1000 && y <= 1000 && (imageWidth > 1000 || imageHeight > 1000)) {
                            int scaledX = (int) Math.round(x * imageWidth / 1000.0);
                            int scaledY = (int) Math.round(y * imageHeight / 1000.0);
                            log.info("[Gemini] findElement — rescaled normalized ({},{}) → ({},{}) for {}x{}",
                                    x, y, scaledX, scaledY, imageWidth, imageHeight);
                            return new int[]{scaledX, scaledY};
                        }
                        if (x >= 0 && x <= imageWidth && y >= 0 && y <= imageHeight) {
                            log.info("[Gemini] findElement — '{}' at ({}, {})", elementDescription, x, y);
                            return new int[]{x, y};
                        }
                        log.warn("[Gemini] findElement — coords out of bounds: ({}, {})", x, y);
                    }
                }
            }
            return null;
        } catch (Exception e) {
            log.info("[Gemini] findElement — FAILED: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Find source and target coordinates for a drag operation in a single Gemini call.
     * Returns int[4] = {sourceX, sourceY, targetX, targetY} or null if not found.
     */
    public int[] findDragCoordinates(Path imagePath, String sourceDescription, String targetDescription,
                                      int imageWidth, int imageHeight) {
        if (!isAvailable() || imagePath == null || !Files.exists(imagePath)) return null;

        try {
            byte[] imageBytes = Files.readAllBytes(imagePath);
            String base64 = Base64.getEncoder().encodeToString(imageBytes);

            String prompt = "You are a precise visual element locator for drag-and-drop operations.\n\n"
                    + "Find TWO elements in this screenshot and return their CENTER pixel coordinates.\n\n"
                    + "SOURCE (drag FROM): " + sourceDescription + "\n"
                    + "TARGET (drag TO): " + targetDescription + "\n\n"
                    + "Image dimensions: " + imageWidth + "x" + imageHeight + " pixels.\n\n"
                    + "RULES:\n"
                    + "- Return EXACTLY two lines in this format:\n"
                    + "  SOURCE:x,y\n"
                    + "  TARGET:x,y\n"
                    + "- x,y are integer pixel coordinates of the CENTER of each element.\n"
                    + "- For chess/board games: identify pieces and squares by their grid position.\n"
                    + "- If either element cannot be found, return: NOT_FOUND\n"
                    + "- Do NOT add any other text or explanation.";

            String response = callGemini(base64, prompt, "image/png", model);
            log.info("[Gemini] findDragCoordinates — source='{}', target='{}', response: '{}'",
                    sourceDescription, targetDescription, truncate(response, 200));

            if (response == null || response.isBlank() || response.contains("NOT_FOUND")) return null;

            int[] source = null, target = null;
            for (String line : response.split("\\r?\\n")) {
                line = line.trim();
                if (line.startsWith("SOURCE:")) {
                    source = parseCoordsLine(line.substring(7), imageWidth, imageHeight);
                } else if (line.startsWith("TARGET:")) {
                    target = parseCoordsLine(line.substring(7), imageWidth, imageHeight);
                }
            }

            if (source != null && target != null) {
                return new int[]{source[0], source[1], target[0], target[1]};
            }
            return null;
        } catch (Exception e) {
            log.info("[Gemini] findDragCoordinates — FAILED: {}", e.getMessage());
            return null;
        }
    }

    private int[] parseCoordsLine(String s, int maxW, int maxH) {
        try {
            String[] parts = s.trim().split(",");
            if (parts.length == 2) {
                int x = Integer.parseInt(parts[0].trim());
                int y = Integer.parseInt(parts[1].trim());
                if (x >= 0 && x <= maxW && y >= 0 && y <= maxH) return new int[]{x, y};
            }
        } catch (NumberFormatException ignored) {}
        return null;
    }

    // ═══ Drag verification (uses reasoning model for higher accuracy) ═══

    private static final String VERIFY_DRAG_PROMPT = """
            You are verifying a drag-and-drop operation on a Windows desktop. \
            Look at this screenshot VERY carefully.

            RULES:
            - Check if the element '%s' is STILL VISIBLE as a desktop icon on the screen.
            - If you can see '%s' as a desktop icon label, the drag FAILED — respond NO.
            - If '%s' is NOT visible as a desktop icon, the drag SUCCEEDED — respond YES.
            - Respond with EXACTLY "YES" or "NO" on the first line.
            - On the second line, list the desktop icon labels you can see.""";

    /**
     * Verify a drag using Gemini's reasoning model.
     * Checks whether the source element is still visible on screen.
     *
     * @param imagePath         path to the screenshot (may be cropped)
     * @param sourceDescription the element that should have been dragged away
     * @return "YES" (source gone, drag succeeded) or "NO" (source still visible), or null
     */
    public String verifyDrag(Path imagePath, String sourceDescription) {
        if (!isAvailable() || imagePath == null || !Files.exists(imagePath)) return null;

        try {
            byte[] imageBytes = Files.readAllBytes(imagePath);
            String base64 = Base64.getEncoder().encodeToString(imageBytes);

            String prompt = VERIFY_DRAG_PROMPT.formatted(sourceDescription, sourceDescription, sourceDescription);

            log.info("[Gemini] verifyDrag — model={}, looking for '{}'", reasoningModel, sourceDescription);
            String response = callGemini(base64, prompt, "image/png", reasoningModel);
            log.info("[Gemini] verifyDrag — source='{}', answer='{}'",
                    sourceDescription, truncate(response, 300));
            return response;
        } catch (Exception e) {
            log.info("[Gemini] verifyDrag — FAILED: {}", e.getMessage());
            return null;
        }
    }

    // ═══ General screenshot analysis ═══

    /**
     * Analyze a screenshot using Gemini Vision (fast model).
     *
     * @param imagePath path to the screenshot
     * @param prompt    what to analyze/describe
     * @return Gemini's response, or null on failure
     */
    public String analyze(Path imagePath, String prompt) {
        if (!isAvailable()) {
            log.warn("[Gemini] analyze — NOT AVAILABLE (apiKey={}, httpClient={})",
                    apiKey != null && !apiKey.isBlank() ? "SET" : "EMPTY",
                    httpClient != null ? "OK" : "NULL");
            return null;
        }
        if (imagePath == null || !Files.exists(imagePath)) {
            log.warn("[Gemini] analyze — image path invalid: {}", imagePath);
            return null;
        }

        try {
            byte[] imageBytes = Files.readAllBytes(imagePath);
            log.info("[Gemini] analyze — image: {} ({} bytes), model: {}, prompt ({} chars):\n{}",
                    imagePath.getFileName(), imageBytes.length, model, prompt.length(),
                    prompt.length() > 500 ? prompt.substring(0, 500) + "..." : prompt);
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            String mediaType = imagePath.toString().toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
            String result = callGemini(base64, prompt, mediaType, model);
            if (result == null || result.isBlank()) {
                log.warn("[Gemini] analyze — callGemini returned null/empty");
            } else {
                if (moduleStats != null) moduleStats.recordVisionCall("Gemini " + model);
                log.info("[Gemini] analyze — SUCCESS ({} chars): {}", result.length(),
                        result.length() > 300 ? result.substring(0, 300) + "..." : result);
            }
            return result;
        } catch (Exception e) {
            log.warn("[Gemini] analyze — FAILED: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Analyze using a specific model override (e.g. "gemini-3.1-pro-preview" for calibration).
     */
    public String analyze(Path imagePath, String prompt, String modelOverride) {
        if (!isAvailable() || imagePath == null || !Files.exists(imagePath)) return null;
        try {
            byte[] imageBytes = Files.readAllBytes(imagePath);
            log.info("[Gemini] analyze — image: {} ({} bytes), model: {}, prompt ({} chars)",
                    imagePath.getFileName(), imageBytes.length, modelOverride, prompt.length());
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            String mediaType = imagePath.toString().toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
            String result = callGemini(base64, prompt, mediaType, modelOverride);
            if (result != null && !result.isBlank()) {
                if (moduleStats != null) moduleStats.recordVisionCall("Gemini " + modelOverride);
                log.info("[Gemini] analyze — SUCCESS ({} chars): {}", result.length(),
                        result.length() > 300 ? result.substring(0, 300) + "..." : result);
            }
            return result;
        } catch (Exception e) {
            log.warn("[Gemini] analyze — FAILED (model={}): {}", modelOverride, e.getMessage());
            return null;
        }
    }

    /**
     * Fast analysis with a short timeout — for watch mode where speed matters more than reliability.
     * Uses a 10-second timeout instead of the default 60s.
     */
    public String analyzeQuick(Path imagePath, String prompt) {
        if (!isAvailable() || imagePath == null || !Files.exists(imagePath)) return null;
        try {
            byte[] imageBytes = Files.readAllBytes(imagePath);
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            String mediaType = imagePath.toString().toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
            return callGemini(base64, prompt, mediaType, model, 10);
        } catch (Exception e) {
            log.warn("[Gemini] analyzeQuick — FAILED: {}", e.getMessage());
            return null;
        }
    }

    // ═══ Core API call ═══

    private String callGemini(String base64Image, String prompt, String mimeType, String modelName) {
        return callGemini(base64Image, prompt, mimeType, modelName, 60);
    }

    private String callGemini(String base64Image, String prompt, String mimeType, String modelName, int timeoutSeconds) {
        try {
            String url = BASE_URL + modelName + ":generateContent?key=" + apiKey;

            String requestBody = buildRequest(base64Image, prompt, mimeType);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("[Gemini] API returned HTTP {} for model '{}': {}", response.statusCode(),
                        modelName, truncate(response.body(), 500));
                return null;
            }

            String content = extractContent(response.body());
            if (content == null || content.isBlank()) {
                log.warn("[Gemini] extractContent returned null/empty from response: {}",
                        truncate(response.body(), 300));
            }
            return content;
        } catch (Exception e) {
            log.warn("[Gemini] API call failed for model '{}': {}", modelName, e.getMessage());
            return null;
        }
    }

    private String buildRequest(String base64Image, String prompt, String mimeType) {
        return "{\"contents\":[{\"parts\":["
                + "{\"inline_data\":{\"mime_type\":\"" + escapeJson(mimeType)
                + "\",\"data\":\"" + base64Image + "\"}},"
                + "{\"text\":\"" + escapeJson(prompt) + "\"}"
                + "]}]}";
    }

    /** Extract text from Gemini generateContent response: candidates[0].content.parts[0].text */
    private static String extractContent(String json) {
        // Find "text" field inside candidates[0].content.parts[0]
        int candidatesIdx = json.indexOf("\"candidates\"");
        if (candidatesIdx < 0) return null;

        int partsIdx = json.indexOf("\"parts\"", candidatesIdx);
        if (partsIdx < 0) return null;

        int textIdx = json.indexOf("\"text\"", partsIdx);
        if (textIdx < 0) return null;

        // Find the value after "text":
        int colonIdx = json.indexOf(':', textIdx + 5);
        if (colonIdx < 0) return null;

        int start = json.indexOf('"', colonIdx + 1);
        if (start < 0) return null;
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
