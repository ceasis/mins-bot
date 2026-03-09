package com.minsbot;

import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.FileInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Base64;
import java.util.Properties;

import javax.imageio.ImageIO;

/**
 * Standalone test for Google Gemini Vision API with Computer Use capability.
 * Right-click → Run As → Java Application in Eclipse.
 *
 * Uses gemini-3-flash-preview model (supports computer use / image input).
 * Takes a screenshot, sends it to Gemini, and asks it to find a UI element.
 */
public class GeminiVisionTest {

    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/";

    public static void main(String[] args) throws Exception {
        // ── 1. Load API key from application-secrets.properties ──
        Properties secrets = new Properties();
        Path secretsFile = Paths.get("application-secrets.properties");
        if (!Files.exists(secretsFile)) {
            secretsFile = Paths.get(System.getProperty("user.dir"), "application-secrets.properties");
        }
        if (!Files.exists(secretsFile)) {
            System.err.println("ERROR: application-secrets.properties not found at: " + secretsFile.toAbsolutePath());
            System.err.println("Create it with: gemini.api.key=AIza...");
            return;
        }
        try (FileInputStream fis = new FileInputStream(secretsFile.toFile())) {
            secrets.load(fis);
        }

        String apiKey = secrets.getProperty("gemini.api.key", "").trim();
        if (apiKey.isEmpty()) {
            System.err.println("ERROR: gemini.api.key is empty in " + secretsFile.toAbsolutePath());
            return;
        }
        System.out.println("[OK] API key loaded: " + apiKey.substring(0, 12) + "...");

        String model = "gemini-3-flash-preview";
        String searchTarget = args.length > 0 ? args[0] : "Start";

        // ── 2. Take a screenshot ──
        System.out.println("[OK] Taking screenshot...");
        Robot robot = new Robot();
        java.awt.Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        BufferedImage screenshot = robot.createScreenCapture(
                new Rectangle(0, 0, screen.width, screen.height));

        Path imgFile = Files.createTempFile("gemini_test_", ".png");
        ImageIO.write(screenshot, "png", imgFile.toFile());
        System.out.println("[OK] Screenshot saved: " + imgFile + " (" + screen.width + "x" + screen.height + ")");

        byte[] imageBytes = Files.readAllBytes(imgFile);
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        System.out.println("[OK] Base64 encoded: " + imageBytes.length + " bytes → " + base64.length() + " chars");

        // ── 3. Build request (standard generateContent with image) ──
        String prompt = "You are a pixel-precise UI element locator. "
                + "Find the element with the text '" + searchTarget + "' in this screenshot and return "
                + "the CENTER coordinates of that text.\n\n"
                + "CRITICAL RULES:\n"
                + "- Return the CENTER of the TEXT itself.\n"
                + "- Image dimensions: " + screen.width + "x" + screen.height + " pixels.\n"
                + "- Return ONLY one line: COORDS:x,y (integers)\n"
                + "- If not found, return: NOT_FOUND";

        String requestBody = buildRequest(base64, prompt);

        String url = BASE_URL + model + ":generateContent?key=" + apiKey;

        System.out.println("[OK] Request built: model=" + model + ", searching for '" + searchTarget + "'");
        System.out.println("[OK] Request body size: " + requestBody.length() + " chars");
        System.out.println("[OK] URL: " + BASE_URL + model + ":generateContent?key=***");

        // ── 4. Send request ──
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        System.out.println("[..] Sending to Gemini API...");
        long t0 = System.currentTimeMillis();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        long elapsed = System.currentTimeMillis() - t0;

        System.out.println("[OK] Response: HTTP " + response.statusCode() + " in " + elapsed + "ms");

        if (response.statusCode() != 200) {
            System.err.println("ERROR: API returned " + response.statusCode());
            System.err.println("Body: " + response.body().substring(0, Math.min(response.body().length(), 500)));
            Files.deleteIfExists(imgFile);
            return;
        }

        // ── 5. Parse response ──
        String body = response.body();
        System.out.println("[OK] Raw response (first 500 chars): " + body.substring(0, Math.min(body.length(), 500)));

        String content = extractContent(body);
        System.out.println("[OK] Extracted content: '" + content + "'");

        if (content.contains("NOT_FOUND")) {
            System.out.println("[RESULT] '" + searchTarget + "' NOT FOUND on screen");
        } else if (content.contains("COORDS:")) {
            for (String line : content.split("\\r?\\n")) {
                line = line.trim();
                if (line.startsWith("COORDS:")) {
                    String[] parts = line.substring(7).split(",");
                    if (parts.length == 2) {
                        int x = Integer.parseInt(parts[0].trim());
                        int y = Integer.parseInt(parts[1].trim());
                        System.out.println("[RESULT] '" + searchTarget + "' found at (" + x + ", " + y + ") in " + elapsed + "ms");
                    }
                    break;
                }
            }
        } else {
            System.out.println("[RESULT] Unexpected response: " + content);
        }

        // ── 6. Test Computer Use endpoint ──
        System.out.println("\n══════════════════════════════════════════════════");
        System.out.println("[..] Testing Computer Use capability...");
        testComputerUse(client, apiKey, model, base64, screen.width, screen.height, searchTarget);

        // Cleanup
        Files.deleteIfExists(imgFile);
        System.out.println("[DONE]");
    }

    /**
     * Test Gemini Computer Use — uses the computer_use_tool definition
     * to let the model interact with the screen as an agent.
     */
    private static void testComputerUse(HttpClient client, String apiKey, String model,
                                          String base64Image, int screenW, int screenH,
                                          String searchTarget) {
        try {
            String url = BASE_URL + model + ":generateContent?key=" + apiKey;

            // Computer Use request format:
            // tools[] contains computer_use tool with display dimensions
            // contents[] has the screenshot + instruction
            String requestBody = "{"
                    + "\"model\":\"" + model + "\","
                    + "\"tools\":[{"
                    + "  \"computerUse\":{"
                    + "    \"environment\":{"
                    + "      \"displayWidthPx\":" + screenW + ","
                    + "      \"displayHeightPx\":" + screenH
                    + "    }"
                    + "  }"
                    + "}],"
                    + "\"contents\":[{"
                    + "  \"role\":\"user\","
                    + "  \"parts\":["
                    + "    {\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"" + base64Image + "\"}},"
                    + "    {\"text\":\"" + escapeJson("Click on the '" + searchTarget + "' button or text element. "
                    + "Look at the screenshot and find where '" + searchTarget + "' is located.") + "\"}"
                    + "  ]"
                    + "}]"
                    + "}";

            System.out.println("[OK] Computer Use request body size: " + requestBody.length() + " chars");

            long t0 = System.currentTimeMillis();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - t0;

            System.out.println("[OK] Computer Use response: HTTP " + response.statusCode() + " in " + elapsed + "ms");

            String body = response.body();
            System.out.println("[OK] Computer Use raw response (first 800 chars):");
            System.out.println(body.substring(0, Math.min(body.length(), 800)));

            if (response.statusCode() == 200) {
                // Look for click coordinates in the response
                // Computer use responses contain functionCall with click actions
                if (body.contains("\"click\"") || body.contains("\"CLICK\"")) {
                    System.out.println("[OK] Model returned a CLICK action!");
                    // Extract x,y from the response
                    extractClickCoords(body);
                } else if (body.contains("\"computerUse\"") || body.contains("\"computer_use\"")) {
                    System.out.println("[OK] Model returned a computer use action!");
                    extractClickCoords(body);
                } else {
                    String content = extractContent(body);
                    System.out.println("[OK] Computer Use text response: " + content);
                }
            } else {
                System.err.println("[ERROR] Computer Use failed: HTTP " + response.statusCode());
                System.err.println("Body: " + body.substring(0, Math.min(body.length(), 500)));
            }
        } catch (Exception e) {
            System.err.println("[ERROR] Computer Use test failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** Try to extract click coordinates from a computer use response. */
    private static void extractClickCoords(String json) {
        // Look for x and y values near click actions
        // Format varies but typically: "x": 123, "y": 456
        try {
            int idx = json.indexOf("\"x\"");
            if (idx > 0) {
                int colonX = json.indexOf(':', idx + 3);
                int endX = findNumberEnd(json, colonX + 1);
                String xStr = json.substring(colonX + 1, endX).trim();

                int idxY = json.indexOf("\"y\"", idx);
                if (idxY > 0) {
                    int colonY = json.indexOf(':', idxY + 3);
                    int endY = findNumberEnd(json, colonY + 1);
                    String yStr = json.substring(colonY + 1, endY).trim();

                    System.out.println("[RESULT] Computer Use click at: (" + xStr + ", " + yStr + ")");
                    return;
                }
            }
            System.out.println("[INFO] Could not extract click coordinates from response. Full response printed above.");
        } catch (Exception e) {
            System.out.println("[INFO] Could not parse click coords: " + e.getMessage());
        }
    }

    private static int findNumberEnd(String s, int start) {
        int i = start;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
        while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
        return i;
    }

    /** Build standard Gemini generateContent request body. */
    private static String buildRequest(String base64Image, String prompt) {
        return "{\"contents\":[{\"parts\":["
                + "{\"inline_data\":{\"mime_type\":\"image/png\",\"data\":\"" + base64Image + "\"}},"
                + "{\"text\":\"" + escapeJson(prompt) + "\"}"
                + "]}]}";
    }

    /** Extract text from Gemini response: candidates[0].content.parts[0].text */
    private static String extractContent(String json) {
        int candidatesIdx = json.indexOf("\"candidates\"");
        if (candidatesIdx < 0) return "[parse error: no candidates field]";

        int partsIdx = json.indexOf("\"parts\"", candidatesIdx);
        if (partsIdx < 0) return "[parse error: no parts field]";

        int textIdx = json.indexOf("\"text\"", partsIdx);
        if (textIdx < 0) return "[parse error: no text field — might be a tool call response]";

        int colonIdx = json.indexOf(':', textIdx + 5);
        if (colonIdx < 0) return "[parse error: no colon after text]";

        int start = json.indexOf('"', colonIdx + 1);
        if (start < 0) return "[parse error: no opening quote]";
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
}
