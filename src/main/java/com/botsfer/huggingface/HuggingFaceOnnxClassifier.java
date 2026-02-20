package com.botsfer.huggingface;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * Runs ONNX image classification models (e.g. suko/nsfw) that expect input shape [1, 224, 224, 3] float NHWC.
 * Reads signature.json for label names if present.
 */
@Component
public class HuggingFaceOnnxClassifier {

    private static final Logger log = LoggerFactory.getLogger(HuggingFaceOnnxClassifier.class);
    private static final int INPUT_SIZE = 224;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Run classification on an image using a cached ONNX model. Returns a human-readable result string.
     * Model dir must contain model.onnx and optionally signature.json (for class names).
     */
    public String classify(Path imagePath, Path modelDir) throws Exception {
        Path onnxPath = modelDir.resolve("model.onnx");
        if (!Files.isRegularFile(onnxPath)) {
            throw new IllegalArgumentException("No model.onnx in " + modelDir);
        }
        float[][][][] imageTensor = loadAndPreprocessImage(imagePath);
        List<String> labels = readLabels(modelDir);

        try (OrtEnvironment env = OrtEnvironment.getEnvironment();
             OrtSession session = env.createSession(onnxPath.toString(), new OrtSession.SessionOptions())) {

            long[] shape = {1, INPUT_SIZE, INPUT_SIZE, 3};
            float[] flat = new float[1 * INPUT_SIZE * INPUT_SIZE * 3];
            int idx = 0;
            for (int y = 0; y < INPUT_SIZE; y++) {
                for (int x = 0; x < INPUT_SIZE; x++) {
                    for (int c = 0; c < 3; c++) {
                        flat[idx++] = imageTensor[0][y][x][c];
                    }
                }
            }
            FloatBuffer buffer = FloatBuffer.wrap(flat);

            String inputName = session.getInputNames().iterator().next();
            try (OnnxTensor inputTensor = OnnxTensor.createTensor(env, buffer, shape)) {
                OrtSession.Result result = session.run(java.util.Map.of(inputName, inputTensor));
                try (result) {
                    OnnxValue outputValue = result.get(0);
                    Object outputObj = outputValue.getValue();
                    float[][] output;
                    if (outputObj instanceof float[][] f) {
                        output = f;
                    } else if (outputObj instanceof float[] f1) {
                        output = new float[][]{f1};
                    } else {
                        return "Unexpected model output type: " + (outputObj != null ? outputObj.getClass() : "null");
                    }
                    if (output == null || output.length == 0 || output[0] == null) {
                        return "Model returned no output.";
                    }
                    float[] scores = output[0];
                    while (labels.size() < scores.length) {
                        labels.add("class_" + labels.size());
                    }
                    StringBuilder sb = new StringBuilder();
                    int maxIdx = 0;
                    for (int i = 0; i < scores.length; i++) {
                        String label = i < labels.size() ? labels.get(i) : "class_" + i;
                        sb.append(label).append(": ").append(String.format("%.2f", scores[i]));
                        if (i < scores.length - 1) sb.append(", ");
                        if (scores[i] > scores[maxIdx]) maxIdx = i;
                    }
                    String topLabel = maxIdx < labels.size() ? labels.get(maxIdx) : "class_" + maxIdx;
                    return topLabel + " (" + String.format("%.2f", scores[maxIdx]) + "). All: " + sb;
                }
            }
        }
    }

    /** Load image and resize to INPUT_SIZE x INPUT_SIZE, normalize to [0,1] float [1][H][W][3]. */
    private float[][][][] loadAndPreprocessImage(Path imagePath) throws Exception {
        BufferedImage img = ImageIO.read(imagePath.toFile());
        if (img == null) throw new IllegalArgumentException("Could not read image: " + imagePath);
        BufferedImage resized = new BufferedImage(INPUT_SIZE, INPUT_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, INPUT_SIZE, INPUT_SIZE, null);
        g.dispose();

        float[][][][] out = new float[1][INPUT_SIZE][INPUT_SIZE][3];
        for (int y = 0; y < INPUT_SIZE; y++) {
            for (int x = 0; x < INPUT_SIZE; x++) {
                int rgb = resized.getRGB(x, y);
                out[0][y][x][0] = ((rgb >> 16) & 0xFF) / 255f;
                out[0][y][x][1] = ((rgb >> 8) & 0xFF) / 255f;
                out[0][y][x][2] = (rgb & 0xFF) / 255f;
            }
        }
        return out;
    }

    private List<String> readLabels(Path modelDir) {
        List<String> labels = new ArrayList<>();
        Path sigPath = modelDir.resolve("signature.json");
        if (Files.exists(sigPath)) {
            try {
                JsonNode sig = OBJECT_MAPPER.readTree(sigPath.toFile());
                if (sig.has("classes") && sig.get("classes").has("Label")) {
                    sig.get("classes").get("Label").forEach(n -> labels.add(n.asText()));
                }
            } catch (Exception e) {
                log.debug("Could not read signature.json: {}", e.getMessage());
            }
        }
        Path labelsPath = modelDir.resolve("labels.txt");
        if (Files.exists(labelsPath) && labels.isEmpty()) {
            try {
                Files.readAllLines(labelsPath).forEach(labels::add);
            } catch (Exception e) {
                log.debug("Could not read labels.txt: {}", e.getMessage());
            }
        }
        return labels;
    }
}
