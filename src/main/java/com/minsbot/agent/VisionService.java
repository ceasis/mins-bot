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

    @Value("${app.vision.verify-model:gpt-4o}")
    private String verifyModel;

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
            log.info("[Vision] analyzeScreenshot — image: {} ({} bytes), model: {}, prompt ({} chars):\n{}",
                    imagePath.getFileName(), imageBytes.length, model, SYSTEM_PROMPT.length(), SYSTEM_PROMPT);
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
                log.warn("[Vision] API returned HTTP {}: {}", response.statusCode(),
                        truncate(response.body(), 200));
                return null;
            }

            String content = extractContent(response.body());
            if (content == null || content.isBlank()) {
                log.warn("[Vision] Empty response from API");
                return null;
            }

            log.info("[Vision] Analysis SUCCESS ({} chars): {}", content.length(),
                    content.length() > 300 ? content.substring(0, 300) + "..." : content);
            return content;

        } catch (Exception e) {
            log.warn("[Vision] Analysis failed: {}", e.getMessage());
            return null;
        }
    }

    private static final String WEBCAM_PROMPT = """
            You are analyzing a webcam photo. Describe what you see:
            1. People visible (appearance, expression, activity — do NOT try to identify anyone by name)
            2. The environment/surroundings (room, desk, lighting, objects)
            3. What the person appears to be doing (working, talking, away from desk, etc.)
            4. Any notable objects or changes from a typical scene

            Be concise but thorough. Do NOT add commentary — just describe what you see.""";

    /**
     * Analyze a screenshot with a custom prompt (for watch mode, etc.).
     * Uses gpt-4o-mini with a 10s timeout for speed.
     */
    public String analyzeWithPrompt(Path imagePath, String prompt) {
        return analyzeWithPrompt(imagePath, prompt, null);
    }

    public String analyzeWithPrompt(Path imagePath, String prompt, String modelOverride) {
        if (!isAvailable()) return null;
        if (imagePath == null || !Files.exists(imagePath)) return null;
        try {
            String useModel = (modelOverride != null && !modelOverride.isBlank()) ? modelOverride : model;
            byte[] imageBytes = Files.readAllBytes(imagePath);
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            String mediaType = imagePath.toString().toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
            String requestBody = buildVisionRequestWithModel(base64, prompt, mediaType, useModel);
            String url = baseUrl.endsWith("/")
                    ? baseUrl + "v1/chat/completions"
                    : baseUrl + "/v1/chat/completions";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[Vision] analyzeWithPrompt — HTTP {}", response.statusCode());
                return null;
            }
            return extractContent(response.body());
        } catch (Exception e) {
            log.warn("[Vision] analyzeWithPrompt — FAILED: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Analyze a webcam photo using the OpenAI Vision API with a webcam-specific prompt.
     *
     * @param imagePath path to a JPEG webcam photo
     * @return the AI description, or null on failure
     */
    public String analyzeWebcamPhoto(Path imagePath) {
        if (!isAvailable()) return null;
        if (imagePath == null || !Files.exists(imagePath)) return null;

        try {
            byte[] imageBytes = Files.readAllBytes(imagePath);
            String base64 = Base64.getEncoder().encodeToString(imageBytes);

            // Determine media type from extension
            String fileName = imagePath.getFileName().toString().toLowerCase();
            String mediaType = fileName.endsWith(".png") ? "image/png" : "image/jpeg";

            String requestBody = buildVisionRequestWithPrompt(base64, WEBCAM_PROMPT, mediaType);

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
                log.debug("[Vision] Webcam API returned HTTP {}: {}", response.statusCode(),
                        truncate(response.body(), 200));
                return null;
            }

            String content = extractContent(response.body());
            if (content == null || content.isBlank()) {
                log.debug("[Vision] Empty webcam response from API");
                return null;
            }

            log.debug("[Vision] Webcam analysis complete ({} chars)", content.length());
            return content;

        } catch (Exception e) {
            log.debug("[Vision] Webcam analysis failed: {}", e.getMessage());
            return null;
        }
    }

    // ═══ Element coordinate detection ═══

    private static final String ELEMENT_FIND_PROMPT = """
            You are a UI element locator. Given a screenshot, find the element described by the user \
            and return its CENTER pixel coordinates.

            RULES:
            - Return ONLY a single line in this exact format: COORDS:x,y
            - x and y are integer pixel coordinates of the CENTER of the element.
            - If the element is not visible or cannot be found, return: NOT_FOUND
            - Do NOT add any other text, explanation, or commentary.

            Example responses:
            COORDS:450,320
            COORDS:1200,580
            NOT_FOUND""";

    /**
     * Find the pixel coordinates of a UI element on screen by description.
     * Takes a screenshot path, sends it to the Vision API with a targeted prompt,
     * and returns the center (x, y) coordinates as an int array, or null if not found.
     *
     * @param imagePath          path to the screenshot PNG
     * @param elementDescription what to find, e.g. "the Selection button", "the search input field"
     * @param screenWidth        actual screen width (for coordinate validation)
     * @param screenHeight       actual screen height (for coordinate validation)
     * @return int[] {x, y} or null if not found
     */
    /**
     * Find element using a specific model (e.g. "gpt-5.4" for calibration).
     */
    public int[] findElementCoordinates(Path imagePath, String elementDescription,
                                         int screenWidth, int screenHeight, String modelOverride) {
        String savedModel = this.model;
        this.model = modelOverride;
        try {
            return findElementCoordinates(imagePath, elementDescription, screenWidth, screenHeight);
        } finally {
            this.model = savedModel;
        }
    }

    public int[] findElementCoordinates(Path imagePath, String elementDescription,
                                         int screenWidth, int screenHeight) {
        if (!isAvailable()) {
            log.info("[Vision] findElement — service not available (no API key?)");
            return null;
        }
        if (imagePath == null || !Files.exists(imagePath)) {
            log.info("[Vision] findElement — image path null or missing: {}", imagePath);
            return null;
        }

        try {
            byte[] imageBytes = Files.readAllBytes(imagePath);
            log.info("[Vision] findElement — image: {} ({} bytes), screen: {}x{}, looking for: '{}'",
                    imagePath.getFileName(), imageBytes.length, screenWidth, screenHeight, elementDescription);

            String base64 = Base64.getEncoder().encodeToString(imageBytes);

            String prompt = ELEMENT_FIND_PROMPT + "\n\nFind this element: " + elementDescription
                    + "\n\nIMPORTANT: The screen resolution is " + screenWidth + "x" + screenHeight
                    + ". Return coordinates within this range.";

            String requestBody = buildVisionRequestWithPrompt(base64, prompt, "image/png");

            String url = baseUrl.endsWith("/")
                    ? baseUrl + "v1/chat/completions"
                    : baseUrl + "/v1/chat/completions";

            log.info("[Vision] findElement — sending to {} (model={})", url, model);

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
                log.info("[Vision] findElement — API returned HTTP {}: {}",
                        response.statusCode(), truncate(response.body(), 300));
                return null;
            }

            String content = extractContent(response.body());
            log.info("[Vision] findElement — raw API response: '{}'", truncate(content, 200));

            if (content == null || content.isBlank() || content.contains("NOT_FOUND")) {
                log.info("[Vision] findElement — element not found: '{}'", elementDescription);
                return null;
            }

            // Parse "COORDS:x,y" format
            String coordLine = content.trim();
            if (coordLine.startsWith("COORDS:")) {
                String[] parts = coordLine.substring(7).split(",");
                if (parts.length == 2) {
                    int x = Integer.parseInt(parts[0].trim());
                    int y = Integer.parseInt(parts[1].trim());
                    log.info("[Vision] findElement — parsed coords: ({}, {}), screen bounds: {}x{}",
                            x, y, screenWidth, screenHeight);
                    // Validate coordinates are within screen bounds
                    if (x >= 0 && x <= screenWidth && y >= 0 && y <= screenHeight) {
                        log.info("[Vision] findElement — RESULT: '{}' at ({}, {})", elementDescription, x, y);
                        return new int[]{x, y};
                    }
                    log.warn("[Vision] findElement — coords OUT OF BOUNDS: ({}, {}) for {}x{} screen",
                            x, y, screenWidth, screenHeight);
                }
            } else {
                log.info("[Vision] findElement — unexpected response format (no COORDS: prefix): '{}'",
                        truncate(content, 200));
            }

            return null;

        } catch (Exception e) {
            log.info("[Vision] findElement — FAILED: {} — {}", e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    // ═══ Action verification (AI-based yes/no check) ═══

    private static final String VERIFY_ACTION_PROMPT = """
            You are verifying if a screen action was completed successfully. \
            Look at the screenshot carefully and answer the question.
            Respond with EXACTLY one of these on the first line:
            YES — if the action was clearly completed
            NO — if the action was NOT completed (element still in original position, etc.)
            UNCLEAR — if you cannot determine from the screenshot
            On the second line, give a brief reason (one sentence).""";

    /**
     * Ask the AI to verify whether an action was completed by analyzing a screenshot.
     * Returns the full AI response (YES/NO/UNCLEAR + reason), or null on failure.
     *
     * @param imagePath path to the screenshot PNG
     * @param question  what to verify, e.g. "Was 'my_file.txt' dragged into the 'TARGET' folder?"
     * @return AI response string, or null on failure
     */
    public String verifyAction(Path imagePath, String question) {
        if (!isAvailable()) return null;
        if (imagePath == null || !Files.exists(imagePath)) return null;

        try {
            byte[] imageBytes = Files.readAllBytes(imagePath);
            String base64 = Base64.getEncoder().encodeToString(imageBytes);

            String prompt = VERIFY_ACTION_PROMPT + "\n\nQuestion: " + question;
            String requestBody = buildVisionRequestWithPrompt(base64, prompt, "image/png");

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
                log.info("[Vision] verifyAction — API returned HTTP {}", response.statusCode());
                return null;
            }

            String content = extractContent(response.body());
            log.info("[Vision] verifyAction — question: '{}', answer: '{}'", question, truncate(content, 200));
            return content;

        } catch (Exception e) {
            log.info("[Vision] verifyAction — FAILED: {}", e.getMessage());
            return null;
        }
    }

    // ═══ Strong verification (uses gpt-4o for higher accuracy) ═══

    private static final String STRONG_VERIFY_PROMPT = """
            You are verifying a drag-and-drop operation on a Windows desktop. \
            Look at this screenshot VERY carefully.

            RULES:
            - Check if the element '%s' is STILL VISIBLE as a desktop icon on the screen.
            - If you can see '%s' as a desktop icon label, the drag FAILED — respond NO.
            - If '%s' is NOT visible as a desktop icon, the drag SUCCEEDED — respond YES.
            - Respond with EXACTLY "YES" or "NO" on the first line.
            - On the second line, list the desktop icon labels you can see.""";

    /**
     * Verify a drag using the stronger model (gpt-4o by default).
     * Asks whether the source element is still visible on screen.
     * Returns "YES" (drag succeeded, source gone) or "NO" (drag failed, source still visible),
     * or null on failure.
     */
    public String verifyDragStrong(Path imagePath, String sourceDescription) {
        if (!isAvailable()) return null;
        if (imagePath == null || !Files.exists(imagePath)) return null;

        try {
            byte[] imageBytes = Files.readAllBytes(imagePath);
            String base64 = Base64.getEncoder().encodeToString(imageBytes);

            String prompt = STRONG_VERIFY_PROMPT.formatted(sourceDescription, sourceDescription, sourceDescription);
            String requestBody = buildVisionRequestWithModel(base64, prompt, "image/png", verifyModel);

            String url = baseUrl.endsWith("/")
                    ? baseUrl + "v1/chat/completions"
                    : baseUrl + "/v1/chat/completions";

            log.info("[Vision] verifyDragStrong — model={}, looking for '{}'", verifyModel, sourceDescription);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.info("[Vision] verifyDragStrong — API returned HTTP {}: {}",
                        response.statusCode(), truncate(response.body(), 200));
                return null;
            }

            String content = extractContent(response.body());
            log.info("[Vision] verifyDragStrong — source='{}', answer='{}'",
                    sourceDescription, truncate(content, 300));
            return content;

        } catch (Exception e) {
            log.info("[Vision] verifyDragStrong — FAILED: {}", e.getMessage());
            return null;
        }
    }

    // ═══ JSON helpers (manual — same pattern as ToolClassifierService) ═══

    private String buildVisionRequestWithModel(String base64Image, String prompt, String mediaType, String modelOverride) {
        // GPT-5.x models require "max_completion_tokens" instead of "max_tokens"
        String tokenParam = modelOverride.startsWith("gpt-5") ? "max_completion_tokens" : "max_tokens";
        return ("{\"model\":\"%s\",\"%s\":1000,\"messages\":[{\"role\":\"user\",\"content\":" +
                "[{\"type\":\"text\",\"text\":\"%s\"},{\"type\":\"image_url\",\"image_url\":" +
                "{\"url\":\"data:%s;base64,%s\",\"detail\":\"%s\"}}]}]}")
                .formatted(
                        escapeJson(modelOverride),
                        tokenParam,
                        escapeJson(prompt),
                        mediaType,
                        base64Image,
                        escapeJson(detail)
                );
    }

    private String buildVisionRequestWithPrompt(String base64Image, String prompt, String mediaType) {
        return buildVisionRequestWithModel(base64Image, prompt, mediaType, model);
    }

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
