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
 * Standalone test for Anthropic Claude Vision API.
 * Right-click → Run As → Java Application in Eclipse.
 *
 * Takes a screenshot of your screen, sends it to Claude, and asks it to find
 * a UI element. Tests the full pipeline: screenshot → base64 → API call → parse.
 */
public class ClaudeVisionTest {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";

    public static void main(String[] args) throws Exception {
        // ── 1. Load API key from application-secrets.properties ──
        Properties secrets = new Properties();
        Path secretsFile = Paths.get("application-secrets.properties");
        if (!Files.exists(secretsFile)) {
            secretsFile = Paths.get(System.getProperty("user.dir"), "application-secrets.properties");
        }
        if (!Files.exists(secretsFile)) {
            System.err.println("ERROR: application-secrets.properties not found at: " + secretsFile.toAbsolutePath());
            System.err.println("Create it with: app.anthropic.api-key=sk-ant-...");
            return;
        }
        try (FileInputStream fis = new FileInputStream(secretsFile.toFile())) {
            secrets.load(fis);
        }

        String apiKey = secrets.getProperty("app.anthropic.api-key", "").trim();
        if (apiKey.isEmpty()) {
            System.err.println("ERROR: app.anthropic.api-key is empty in " + secretsFile.toAbsolutePath());
            return;
        }
        System.out.println("[OK] API key loaded: " + apiKey.substring(0, 12) + "...");

        String model = "claude-opus-4-6";
        String searchTarget = args.length > 0 ? args[0] : "Start";

        // ── 2. Take a screenshot ──
        System.out.println("[OK] Taking screenshot...");
        Robot robot = new Robot();
        java.awt.Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        BufferedImage screenshot = robot.createScreenCapture(
                new Rectangle(0, 0, screen.width, screen.height));

        Path imgFile = Files.createTempFile("claude_test_", ".png");
        ImageIO.write(screenshot, "png", imgFile.toFile());
        System.out.println("[OK] Screenshot saved: " + imgFile + " (" + screen.width + "x" + screen.height + ")");

        byte[] imageBytes = Files.readAllBytes(imgFile);
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        System.out.println("[OK] Base64 encoded: " + imageBytes.length + " bytes → " + base64.length() + " chars");

        // ── 3. Build request ──
        String prompt = "You are a pixel-precise UI element locator. "
                + "Find the element with the text '" + searchTarget + "' in this screenshot and return "
                + "the CENTER coordinates of that text.\n\n"
                + "CRITICAL RULES:\n"
                + "- Return the CENTER of the TEXT itself.\n"
                + "- Image dimensions: " + screen.width + "x" + screen.height + " pixels.\n"
                + "- Return ONLY one line: COORDS:x,y (integers)\n"
                + "- If not found, return: NOT_FOUND";

        String requestBody = String.format(
                "{\"model\":\"%s\",\"max_tokens\":256,\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"%s\"}},{\"type\":\"text\",\"text\":\"%s\"}]}]}",
                model, base64, escapeJson(prompt));

        System.out.println("[OK] Request built: model=" + model + ", searching for '" + searchTarget + "'");
        System.out.println("[OK] Request body size: " + requestBody.length() + " chars");

        // ── 4. Send request ──
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        System.out.println("[..] Sending to Anthropic API...");
        long t0 = System.currentTimeMillis();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .timeout(Duration.ofSeconds(30))
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
        System.out.println("[OK] Raw response (first 300 chars): " + body.substring(0, Math.min(body.length(), 300)));

        String content = extractContent(body);
        System.out.println("[OK] Extracted content: '" + content + "'");

        if (content.contains("NOT_FOUND")) {
            System.out.println("[RESULT] '" + searchTarget + "' NOT FOUND on screen");
        } else if (content.startsWith("COORDS:")) {
            String[] parts = content.substring(7).split(",");
            if (parts.length == 2) {
                int x = Integer.parseInt(parts[0].trim());
                int y = Integer.parseInt(parts[1].trim());
                System.out.println("[RESULT] '" + searchTarget + "' found at (" + x + ", " + y + ") in " + elapsed + "ms");
            }
        } else {
            System.out.println("[RESULT] Unexpected response: " + content);
        }

        // Cleanup
        Files.deleteIfExists(imgFile);
        System.out.println("[DONE]");
    }

    /** Extract text content from Anthropic Messages API response. */
    private static String extractContent(String json) {
        // Response: {"content":[{"type":"text","text":"COORDS:x,y"}],...}
        // Skip first "text": (which is "type":"text"), use second "text": (actual content)
        int contentIdx = json.indexOf("\"content\"");
        if (contentIdx < 0) return "[parse error: no content field]";
        int firstText = json.indexOf("\"text\":", contentIdx);
        if (firstText < 0) return "[parse error: no first text field]";
        int textIdx = json.indexOf("\"text\":", firstText + 7);
        if (textIdx < 0) return "[parse error: no second text field]";
        int start = json.indexOf('"', textIdx + 7);
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
