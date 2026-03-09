package com.minsbot.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.*;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * AWS Rekognition-based label/object detection for screenshots.
 * Better than Textract for identifying symbols, icons, and non-text elements (e.g. chess pieces).
 * Returns center coordinates in image-pixel space (same format as other findTextOnScreen methods).
 */
@Component
public class RekognitionService {

    private static final Logger log = LoggerFactory.getLogger(RekognitionService.class);

    @Value("${app.rekognition.access-key:${aws.access.key:}}")
    private String accessKey;

    @Value("${app.rekognition.secret-key:${aws.secret.key:}}")
    private String secretKey;

    @Value("${app.rekognition.region:${aws.region:us-east-1}}")
    private String region;

    private RekognitionClient client;

    @PostConstruct
    void init() {
        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            log.info("[Rekognition] No AWS credentials — Rekognition disabled");
            return;
        }
        try {
            client = RekognitionClient.builder()
                    .region(Region.of(region))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKey, secretKey)))
                    .build();
            log.info("[Rekognition] Ready (region={})", region);
        } catch (Exception e) {
            log.warn("[Rekognition] Failed to initialize: {}", e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        if (client != null) {
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    /** True if Rekognition is configured and ready. */
    public boolean isAvailable() {
        return client != null;
    }

    /** A detected label with bounding box in image-pixel coordinates. */
    public record RekognitionLabel(String name, float confidence, double x, double y, double width, double height) {}

    /**
     * Detect labels (objects/symbols) in an image using AWS Rekognition.
     * Returns label-level results with bounding boxes in image-pixel coordinates.
     */
    public List<RekognitionLabel> detectLabels(Path imagePath) {
        List<RekognitionLabel> labels = new ArrayList<>();
        if (!isAvailable() || imagePath == null || !Files.exists(imagePath)) return labels;

        try {
            byte[] imageBytes = Files.readAllBytes(imagePath);
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(imagePath.toFile());
            if (img == null) return labels;
            int imgW = img.getWidth();
            int imgH = img.getHeight();

            DetectLabelsRequest request = DetectLabelsRequest.builder()
                    .image(Image.builder()
                            .bytes(SdkBytes.fromByteArray(imageBytes))
                            .build())
                    .maxLabels(50)
                    .minConfidence(30f)
                    .build();

            DetectLabelsResponse response = client.detectLabels(request);

            for (Label label : response.labels()) {
                if (label.instances() != null) {
                    for (Instance instance : label.instances()) {
                        if (instance.boundingBox() != null) {
                            BoundingBox bb = instance.boundingBox();
                            double px = bb.left() * imgW;
                            double py = bb.top() * imgH;
                            double pw = bb.width() * imgW;
                            double ph = bb.height() * imgH;
                            labels.add(new RekognitionLabel(label.name(), label.confidence(), px, py, pw, ph));
                        }
                    }
                }
                // Also add labels without instances (no bounding box) for logging
                if (label.instances() == null || label.instances().isEmpty()) {
                    labels.add(new RekognitionLabel(label.name(), label.confidence(), -1, -1, 0, 0));
                }
            }

            log.info("[Rekognition] Detected {} labels from {}", labels.size(), imagePath.getFileName());
        } catch (Exception e) {
            log.warn("[Rekognition] Label detection failed: {}", e.getMessage());
        }
        return labels;
    }

    /**
     * Detect text in an image using AWS Rekognition detectText API.
     * Returns text detections with bounding boxes in image-pixel coordinates.
     */
    public List<RekognitionLabel> detectText(Path imagePath) {
        List<RekognitionLabel> results = new ArrayList<>();
        if (!isAvailable() || imagePath == null || !Files.exists(imagePath)) return results;

        try {
            byte[] imageBytes = Files.readAllBytes(imagePath);
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(imagePath.toFile());
            if (img == null) return results;
            int imgW = img.getWidth();
            int imgH = img.getHeight();

            DetectTextRequest request = DetectTextRequest.builder()
                    .image(Image.builder()
                            .bytes(SdkBytes.fromByteArray(imageBytes))
                            .build())
                    .build();

            DetectTextResponse response = client.detectText(request);

            for (TextDetection td : response.textDetections()) {
                if (td.type() == TextTypes.WORD && td.geometry() != null
                        && td.geometry().boundingBox() != null) {
                    BoundingBox bb = td.geometry().boundingBox();
                    double px = bb.left() * imgW;
                    double py = bb.top() * imgH;
                    double pw = bb.width() * imgW;
                    double ph = bb.height() * imgH;
                    results.add(new RekognitionLabel(td.detectedText(), td.confidence(), px, py, pw, ph));
                }
            }

            log.info("[Rekognition] Detected {} text items from {}", results.size(), imagePath.getFileName());
        } catch (Exception e) {
            log.warn("[Rekognition] Text detection failed: {}", e.getMessage());
        }
        return results;
    }

    /**
     * Find a target on screen using Rekognition.
     * First tries detectText (for text labels), then detectLabels (for symbols/objects).
     * Returns center coordinates in image-pixel space, or null if not found.
     */
    public double[] findTextOnScreen(Path imagePath, String searchText) {
        if (!isAvailable()) return null;

        String search = searchText.trim();
        String searchLower = search.toLowerCase();

        // Strategy 1: Exact text match (single word or full phrase)
        List<RekognitionLabel> textResults = detectText(imagePath);
        for (RekognitionLabel r : textResults) {
            if (r.x() >= 0 && r.name().equalsIgnoreCase(search)) {
                double cx = r.x() + r.width() / 2.0;
                double cy = r.y() + r.height() / 2.0;
                log.info("[Rekognition] Exact text match: '{}' for '{}' at center({},{})", r.name(), search, cx, cy);
                return new double[]{cx, cy};
            }
        }

        // Strategy 2: Multi-word exact text match (e.g. "Red" + "Hawk" = "Red Hawk")
        for (int i = 0; i < textResults.size(); i++) {
            if (textResults.get(i).x() < 0) continue;
            StringBuilder joined = new StringBuilder(textResults.get(i).name());
            double startX = textResults.get(i).x(), startY = textResults.get(i).y();
            double endX = startX + textResults.get(i).width();
            double endY = startY + textResults.get(i).height();

            if (joined.toString().equalsIgnoreCase(search)) {
                double cx = (startX + endX) / 2.0;
                double cy = (startY + endY) / 2.0;
                log.info("[Rekognition] Exact text: '{}' at center({},{})", joined, cx, cy);
                return new double[]{cx, cy};
            }

            for (int j = i + 1; j < Math.min(i + 5, textResults.size()); j++) {
                if (textResults.get(j).x() < 0) continue;
                joined.append(" ").append(textResults.get(j).name());
                endX = Math.max(endX, textResults.get(j).x() + textResults.get(j).width());
                endY = Math.max(endY, textResults.get(j).y() + textResults.get(j).height());

                if (joined.toString().equalsIgnoreCase(search)) {
                    double cx = (startX + endX) / 2.0;
                    double cy = (startY + endY) / 2.0;
                    log.info("[Rekognition] Exact multi-word text: '{}' at center({},{})", joined, cx, cy);
                    return new double[]{cx, cy};
                }
            }
        }

        // Strategy 3: Text contains match (but only if the detected text is substantial)
        for (RekognitionLabel r : textResults) {
            if (r.x() >= 0 && r.name().toLowerCase().contains(searchLower)) {
                double cx = r.x() + r.width() / 2.0;
                double cy = r.y() + r.height() / 2.0;
                log.info("[Rekognition] Text contains: '{}' for '{}' at center({},{})", r.name(), search, cx, cy);
                return new double[]{cx, cy};
            }
        }

        // Strategy 4: Multi-word contains
        for (int i = 0; i < textResults.size(); i++) {
            if (textResults.get(i).x() < 0) continue;
            StringBuilder joined = new StringBuilder(textResults.get(i).name());
            double startX = textResults.get(i).x(), startY = textResults.get(i).y();
            double endX = startX + textResults.get(i).width();
            double endY = startY + textResults.get(i).height();

            for (int j = i + 1; j < Math.min(i + 5, textResults.size()); j++) {
                if (textResults.get(j).x() < 0) continue;
                joined.append(" ").append(textResults.get(j).name());
                endX = Math.max(endX, textResults.get(j).x() + textResults.get(j).width());
                endY = Math.max(endY, textResults.get(j).y() + textResults.get(j).height());

                if (joined.toString().toLowerCase().contains(searchLower)) {
                    double cx = (startX + endX) / 2.0;
                    double cy = (startY + endY) / 2.0;
                    log.info("[Rekognition] Multi-word contains: '{}' at center({},{})", joined, cx, cy);
                    return new double[]{cx, cy};
                }
            }
        }

        // Strategy 5: Use detectLabels for symbol/object matching (chess pieces, icons)
        List<RekognitionLabel> labelResults = detectLabels(imagePath);
        for (RekognitionLabel r : labelResults) {
            if (r.x() >= 0 && (r.name().equalsIgnoreCase(search)
                    || r.name().toLowerCase().contains(searchLower))) {
                double cx = r.x() + r.width() / 2.0;
                double cy = r.y() + r.height() / 2.0;
                log.info("[Rekognition] Label match: '{}' for '{}' at center({},{}) conf={}%",
                        r.name(), search, cx, cy, r.confidence());
                return new double[]{cx, cy};
            }
        }

        log.info("[Rekognition] '{}' not found (text={}, labels={})", search, textResults.size(), labelResults.size());
        return null;
    }
}
