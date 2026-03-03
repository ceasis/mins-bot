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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Google Cloud Document AI OCR for screenshots.
 * Uses the Document AI REST API directly (no SDK dependency).
 * Returns word-level bounding boxes in image-pixel coordinates — same interface as TextractService.
 *
 * <p>Requires a Document AI processor to be set up in Google Cloud Console.
 * Configure via application.properties:
 * <pre>
 * app.document-ai.project-id=your-gcp-project
 * app.document-ai.location=us           # or eu
 * app.document-ai.processor-id=abc123   # from Document AI console
 * app.document-ai.api-key=AIza...       # API key with Document AI access
 * </pre>
 */
@Component
public class DocumentAiService {

    private static final Logger log = LoggerFactory.getLogger(DocumentAiService.class);

    @Value("${app.document-ai.project-id:}")
    private String projectId;

    @Value("${app.document-ai.location:us}")
    private String location;

    @Value("${app.document-ai.processor-id:}")
    private String processorId;

    @Value("${app.document-ai.api-key:}")
    private String apiKey;

    private HttpClient httpClient;

    @PostConstruct
    void init() {
        if (isConfigured()) {
            httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            log.info("[DocumentAI] Ready (project={}, location={}, processor={})",
                    projectId, location, processorId);
        } else {
            log.info("[DocumentAI] Not configured — disabled");
        }
    }

    private boolean isConfigured() {
        return projectId != null && !projectId.isBlank()
                && processorId != null && !processorId.isBlank()
                && apiKey != null && !apiKey.isBlank();
    }

    public boolean isAvailable() {
        return isConfigured() && httpClient != null;
    }

    /** A detected word with bounding box in image-pixel coordinates. */
    public record DocAiWord(String text, double x, double y, double width, double height) {}

    /**
     * Detect all text words in an image using Google Cloud Document AI.
     * Returns word-level results with bounding boxes in image-pixel coordinates.
     */
    public List<DocAiWord> detectWords(Path imagePath) {
        List<DocAiWord> words = new ArrayList<>();
        if (!isAvailable() || imagePath == null || !Files.exists(imagePath)) return words;

        try {
            // Get image dimensions for coordinate conversion
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(imagePath.toFile());
            if (img == null) return words;
            int imgW = img.getWidth();
            int imgH = img.getHeight();

            // Base64-encode the image
            byte[] imageBytes = Files.readAllBytes(imagePath);
            String base64 = Base64.getEncoder().encodeToString(imageBytes);

            // Determine MIME type
            String fileName = imagePath.getFileName().toString().toLowerCase();
            String mimeType = fileName.endsWith(".png") ? "image/png" : "image/jpeg";

            // Build request JSON
            String requestBody = "{\"rawDocument\":{\"content\":\"" + base64
                    + "\",\"mimeType\":\"" + mimeType + "\"}}";

            // Document AI REST endpoint
            String url = String.format(
                    "https://%s-documentai.googleapis.com/v1/projects/%s/locations/%s/processors/%s:process?key=%s",
                    location, projectId, location, processorId, apiKey);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("[DocumentAI] API returned HTTP {} — {}",
                        response.statusCode(),
                        response.body().length() > 300 ? response.body().substring(0, 300) : response.body());
                return words;
            }

            // Parse the response — extract words with bounding boxes
            words = parseWordsFromResponse(response.body(), imgW, imgH);
            log.info("[DocumentAI] Detected {} words from {}", words.size(), imagePath.getFileName());

        } catch (Exception e) {
            log.warn("[DocumentAI] Detection failed: {}", e.getMessage());
        }
        return words;
    }

    /**
     * Find text on a screenshot using Google Cloud Document AI.
     * Returns center coordinates in image-pixel space, or null if not found.
     * Same interface as TextractService.findTextOnScreen.
     */
    public double[] findTextOnScreen(Path imagePath, String searchText) {
        List<DocAiWord> words = detectWords(imagePath);
        if (words.isEmpty()) return null;

        String search = searchText.trim();
        String searchLower = search.toLowerCase();

        log.info("[DocumentAI] Searching for '{}' among {} words", search, words.size());

        // Strategy 1: Exact single-word match
        for (DocAiWord w : words) {
            if (w.text().equalsIgnoreCase(search)) {
                double cx = w.x() + w.width() / 2.0;
                double cy = w.y() + w.height() / 2.0;
                log.info("[DocumentAI] Exact match: '{}' at center({},{})", w.text(), cx, cy);
                return new double[]{cx, cy};
            }
        }

        // Strategy 2: Contains match
        for (DocAiWord w : words) {
            if (w.text().toLowerCase().contains(searchLower)) {
                double cx = w.x() + w.width() / 2.0;
                double cy = w.y() + w.height() / 2.0;
                log.info("[DocumentAI] Contains match: '{}' in '{}' at center({},{})", search, w.text(), cx, cy);
                return new double[]{cx, cy};
            }
        }

        // Strategy 3: Multi-word sliding window
        for (int i = 0; i < words.size(); i++) {
            StringBuilder joined = new StringBuilder(words.get(i).text());
            double startX = words.get(i).x(), startY = words.get(i).y();
            double endX = words.get(i).x() + words.get(i).width();
            double endY = words.get(i).y() + words.get(i).height();

            if (joined.toString().toLowerCase().contains(searchLower)) {
                double cx = (startX + endX) / 2.0;
                double cy = (startY + endY) / 2.0;
                return new double[]{cx, cy};
            }

            for (int j = i + 1; j < Math.min(i + 5, words.size()); j++) {
                joined.append(" ").append(words.get(j).text());
                endX = Math.max(endX, words.get(j).x() + words.get(j).width());
                endY = Math.max(endY, words.get(j).y() + words.get(j).height());

                if (joined.toString().toLowerCase().contains(searchLower)) {
                    double cx = (startX + endX) / 2.0;
                    double cy = (startY + endY) / 2.0;
                    log.info("[DocumentAI] Multi-word match: '{}' at center({},{})", joined, cx, cy);
                    return new double[]{cx, cy};
                }
            }
        }

        // Strategy 4: No-space join
        for (int i = 0; i < words.size(); i++) {
            StringBuilder noSpace = new StringBuilder(words.get(i).text());
            double startX = words.get(i).x(), startY = words.get(i).y();
            double endX = words.get(i).x() + words.get(i).width();
            double endY = words.get(i).y() + words.get(i).height();

            for (int j = i + 1; j < Math.min(i + 4, words.size()); j++) {
                noSpace.append(words.get(j).text());
                endX = Math.max(endX, words.get(j).x() + words.get(j).width());
                endY = Math.max(endY, words.get(j).y() + words.get(j).height());

                if (noSpace.toString().toLowerCase().contains(searchLower)) {
                    double cx = (startX + endX) / 2.0;
                    double cy = (startY + endY) / 2.0;
                    log.info("[DocumentAI] NoSpace match: '{}' at center({},{})", noSpace, cx, cy);
                    return new double[]{cx, cy};
                }
            }
        }

        log.info("[DocumentAI] '{}' not found among {} words", search, words.size());
        return null;
    }

    // ═══ Response parsing ═══

    /**
     * Parse word-level tokens from Document AI JSON response.
     * Document AI returns pages[].tokens[] with layout.textAnchor and layout.boundingPoly.normalizedVertices.
     * Normalized vertices are 0.0–1.0, converted to image pixels.
     */
    private List<DocAiWord> parseWordsFromResponse(String json, int imgW, int imgH) {
        List<DocAiWord> words = new ArrayList<>();

        // Find the "text" field (full document text)
        String fullText = extractJsonStringField(json, "\"text\"");

        // Parse tokens — each has textAnchor (startIndex/endIndex into fullText) and boundingPoly
        int searchFrom = 0;
        while (true) {
            int tokenIdx = json.indexOf("\"textAnchor\"", searchFrom);
            if (tokenIdx < 0) break;

            // Extract startIndex and endIndex
            int startIdxPos = json.indexOf("\"startIndex\"", tokenIdx);
            int endIdxPos = json.indexOf("\"endIndex\"", tokenIdx);

            // Find the next boundingPoly after this token
            int polyIdx = json.indexOf("\"normalizedVertices\"", tokenIdx);
            if (polyIdx < 0) { searchFrom = tokenIdx + 12; continue; }

            // Don't go past the next token
            int nextToken = json.indexOf("\"textAnchor\"", tokenIdx + 12);
            if (nextToken > 0 && polyIdx > nextToken) { searchFrom = tokenIdx + 12; continue; }

            try {
                // Parse text from textAnchor indices
                String wordText = null;
                if (startIdxPos > 0 && endIdxPos > 0 && fullText != null
                        && (nextToken < 0 || (startIdxPos < nextToken && endIdxPos < nextToken))) {
                    int startIdx = extractIntAfterColon(json, startIdxPos);
                    int endIdx = extractIntAfterColon(json, endIdxPos);
                    if (startIdx >= 0 && endIdx > startIdx && endIdx <= fullText.length()) {
                        wordText = fullText.substring(startIdx, endIdx).trim();
                    }
                }

                if (wordText == null || wordText.isBlank()) {
                    searchFrom = tokenIdx + 12;
                    continue;
                }

                // Parse normalizedVertices — typically 4 vertices for a bounding box
                double minX = 1.0, minY = 1.0, maxX = 0.0, maxY = 0.0;
                int verticesStart = polyIdx;
                int verticesEnd = json.indexOf(']', verticesStart);
                if (verticesEnd < 0) { searchFrom = tokenIdx + 12; continue; }

                String verticesBlock = json.substring(verticesStart, verticesEnd);
                int vIdx = 0;
                while (true) {
                    int xPos = verticesBlock.indexOf("\"x\"", vIdx);
                    int yPos = verticesBlock.indexOf("\"y\"", vIdx);
                    if (xPos < 0 || yPos < 0) break;

                    double vx = extractDoubleAfterColon(verticesBlock, xPos);
                    double vy = extractDoubleAfterColon(verticesBlock, yPos);
                    if (vx >= 0) { minX = Math.min(minX, vx); maxX = Math.max(maxX, vx); }
                    if (vy >= 0) { minY = Math.min(minY, vy); maxY = Math.max(maxY, vy); }
                    vIdx = Math.max(xPos, yPos) + 3;
                }

                if (maxX > minX && maxY > minY) {
                    double px = minX * imgW;
                    double py = minY * imgH;
                    double pw = (maxX - minX) * imgW;
                    double ph = (maxY - minY) * imgH;
                    words.add(new DocAiWord(wordText, px, py, pw, ph));
                }
            } catch (Exception e) {
                log.debug("[DocumentAI] Parse error at offset {}: {}", tokenIdx, e.getMessage());
            }

            searchFrom = tokenIdx + 12;
        }

        return words;
    }

    /** Extract a JSON string value following a key like "text": "value". */
    private static String extractJsonStringField(String json, String key) {
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) return null;
        int start = json.indexOf('"', colon + 1);
        if (start < 0) return null;
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
        return sb.toString();
    }

    /** Extract an integer value after a colon, e.g. "startIndex": 42 */
    private static int extractIntAfterColon(String json, int keyPos) {
        int colon = json.indexOf(':', keyPos);
        if (colon < 0) return -1;
        int i = colon + 1;
        while (i < json.length() && (json.charAt(i) == ' ' || json.charAt(i) == '"')) i++;
        StringBuilder num = new StringBuilder();
        while (i < json.length() && Character.isDigit(json.charAt(i))) {
            num.append(json.charAt(i));
            i++;
        }
        return num.length() > 0 ? Integer.parseInt(num.toString()) : -1;
    }

    /** Extract a double value after a colon, e.g. "x": 0.123 */
    private static double extractDoubleAfterColon(String json, int keyPos) {
        int colon = json.indexOf(':', keyPos);
        if (colon < 0) return -1;
        int i = colon + 1;
        while (i < json.length() && json.charAt(i) == ' ') i++;
        StringBuilder num = new StringBuilder();
        while (i < json.length() && (Character.isDigit(json.charAt(i)) || json.charAt(i) == '.' || json.charAt(i) == '-')) {
            num.append(json.charAt(i));
            i++;
        }
        return num.length() > 0 ? Double.parseDouble(num.toString()) : -1;
    }
}
