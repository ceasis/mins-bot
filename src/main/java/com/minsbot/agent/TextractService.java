package com.minsbot.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.textract.TextractClient;
import software.amazon.awssdk.services.textract.model.Block;
import software.amazon.awssdk.services.textract.model.BlockType;
import software.amazon.awssdk.services.textract.model.BoundingBox;
import software.amazon.awssdk.services.textract.model.DetectDocumentTextRequest;
import software.amazon.awssdk.services.textract.model.DetectDocumentTextResponse;
import software.amazon.awssdk.services.textract.model.Document;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * AWS Textract-based text detection for screenshots.
 * Better than Windows OCR for white text on dark backgrounds.
 * Returns center coordinates in image-pixel space (same format as ScreenMemoryService.findTextOnScreen).
 */
@Component
public class TextractService {

    private static final Logger log = LoggerFactory.getLogger(TextractService.class);

    @Value("${app.textract.access-key:}")
    private String accessKey;

    @Value("${app.textract.secret-key:}")
    private String secretKey;

    @Value("${app.textract.region:us-east-1}")
    private String region;

    private TextractClient client;

    @PostConstruct
    void init() {
        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            log.info("[Textract] No AWS credentials — Textract disabled");
            return;
        }
        try {
            client = TextractClient.builder()
                    .region(Region.of(region))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKey, secretKey)))
                    .build();
            log.info("[Textract] Ready (region={})", region);
        } catch (Exception e) {
            log.warn("[Textract] Failed to initialize: {}", e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        if (client != null) {
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    /** True if Textract is configured and ready. */
    public boolean isAvailable() {
        return client != null;
    }

    /** A detected word with bounding box in image-pixel coordinates. */
    public record TextractWord(String text, double x, double y, double width, double height) {}

    /**
     * Detect all text words in an image using AWS Textract.
     * Returns word-level results with bounding boxes in image-pixel coordinates.
     */
    public List<TextractWord> detectWords(Path imagePath) {
        List<TextractWord> words = new ArrayList<>();
        if (!isAvailable() || imagePath == null || !Files.exists(imagePath)) return words;

        try {
            byte[] imageBytes = Files.readAllBytes(imagePath);

            // Get image dimensions for coordinate conversion
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(imagePath.toFile());
            if (img == null) return words;
            int imgW = img.getWidth();
            int imgH = img.getHeight();

            DetectDocumentTextRequest request = DetectDocumentTextRequest.builder()
                    .document(Document.builder()
                            .bytes(SdkBytes.fromByteArray(imageBytes))
                            .build())
                    .build();

            DetectDocumentTextResponse response = client.detectDocumentText(request);

            for (Block block : response.blocks()) {
                if (block.blockType() == BlockType.WORD && block.geometry() != null
                        && block.geometry().boundingBox() != null) {
                    BoundingBox bb = block.geometry().boundingBox();
                    // Textract returns normalized coordinates (0.0 - 1.0) — convert to image pixels
                    double px = bb.left() * imgW;
                    double py = bb.top() * imgH;
                    double pw = bb.width() * imgW;
                    double ph = bb.height() * imgH;
                    words.add(new TextractWord(block.text(), px, py, pw, ph));
                }
            }

            log.info("[Textract] Detected {} words from {}", words.size(), imagePath.getFileName());
        } catch (Exception e) {
            log.warn("[Textract] Detection failed: {}", e.getMessage());
        }
        return words;
    }

    /**
     * Find text on a screenshot image using AWS Textract.
     * Returns center coordinates in image-pixel space, or null if not found.
     * Same return format as ScreenMemoryService.findTextOnScreen.
     */
    public double[] findTextOnScreen(Path imagePath, String searchText) {
        List<TextractWord> words = detectWords(imagePath);
        if (words.isEmpty()) return null;

        String search = searchText.trim();
        String searchLower = search.toLowerCase();

        log.info("[Textract] Searching for '{}' among {} words", search, words.size());

        // Strategy 1: Multi-word exact match first (e.g. "Red Hawk" across "Red" + "Hawk")
        for (int i = 0; i < words.size(); i++) {
            StringBuilder joined = new StringBuilder(words.get(i).text());
            double startX = words.get(i).x(), startY = words.get(i).y();
            double endX = words.get(i).x() + words.get(i).width();
            double endY = words.get(i).y() + words.get(i).height();

            if (joined.toString().equalsIgnoreCase(search)) {
                double cx = (startX + endX) / 2.0;
                double cy = (startY + endY) / 2.0;
                log.info("[Textract] Exact match: '{}' at center({},{})", joined, cx, cy);
                return new double[]{cx, cy};
            }

            for (int j = i + 1; j < Math.min(i + 5, words.size()); j++) {
                joined.append(" ").append(words.get(j).text());
                endX = Math.max(endX, words.get(j).x() + words.get(j).width());
                endY = Math.max(endY, words.get(j).y() + words.get(j).height());

                if (joined.toString().equalsIgnoreCase(search)) {
                    double cx = (startX + endX) / 2.0;
                    double cy = (startY + endY) / 2.0;
                    log.info("[Textract] Exact multi-word match: '{}' at center({},{})", joined, cx, cy);
                    return new double[]{cx, cy};
                }
            }
        }

        // Strategy 2: Multi-word contains (search is substring of joined words)
        for (int i = 0; i < words.size(); i++) {
            StringBuilder joined = new StringBuilder(words.get(i).text());
            double startX = words.get(i).x(), startY = words.get(i).y();
            double endX = words.get(i).x() + words.get(i).width();
            double endY = words.get(i).y() + words.get(i).height();

            if (joined.toString().toLowerCase().contains(searchLower) && joined.length() <= search.length() * 2) {
                double cx = (startX + endX) / 2.0;
                double cy = (startY + endY) / 2.0;
                log.info("[Textract] Contains match: '{}' at center({},{})", joined, cx, cy);
                return new double[]{cx, cy};
            }

            for (int j = i + 1; j < Math.min(i + 5, words.size()); j++) {
                joined.append(" ").append(words.get(j).text());
                endX = Math.max(endX, words.get(j).x() + words.get(j).width());
                endY = Math.max(endY, words.get(j).y() + words.get(j).height());

                if (joined.toString().toLowerCase().contains(searchLower)) {
                    double cx = (startX + endX) / 2.0;
                    double cy = (startY + endY) / 2.0;
                    log.info("[Textract] Multi-word contains: '{}' at center({},{})", joined, cx, cy);
                    return new double[]{cx, cy};
                }
            }
        }

        // Strategy 3: No-space join (handles "ANIMALS" + ".txt" → "ANIMALS.txt")
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
                    log.info("[Textract] NoSpace match: '{}' at center({},{})", noSpace, cx, cy);
                    return new double[]{cx, cy};
                }
            }
        }

        log.info("[Textract] '{}' not found among {} words", search, words.size());
        return null;
    }
}
