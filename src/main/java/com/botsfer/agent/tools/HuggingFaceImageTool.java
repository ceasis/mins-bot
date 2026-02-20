package com.botsfer.agent.tools;

import com.botsfer.huggingface.HuggingFaceOnnxClassifier;
import com.botsfer.huggingface.HuggingFaceService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Tools to discover Hugging Face image-classification models and run local ONNX inference
 * (e.g. determine if an image is censored/NSFW). Models are downloaded and cached automatically.
 */
@Component
public class HuggingFaceImageTool {

    private final HuggingFaceService hfService;
    private final HuggingFaceOnnxClassifier classifier;
    private final ToolExecutionNotifier notifier;

    public HuggingFaceImageTool(HuggingFaceService hfService,
                                HuggingFaceOnnxClassifier classifier,
                                ToolExecutionNotifier notifier) {
        this.hfService = hfService;
        this.classifier = classifier;
        this.notifier = notifier;
    }

    @Tool(description = "Search Hugging Face for image-classification models by keyword (e.g. 'nsfw', 'censored', 'content moderation'). Returns model IDs and tags so you can pick one for classifyImageWithHf.")
    public String searchHuggingFaceImageModels(
            @ToolParam(description = "Search term, e.g. nsfw, censored, safe") String search) {
        notifier.notify("Searching Hugging Face for image models...");
        try {
            List<HuggingFaceService.HfModelInfo> models = hfService.listImageClassificationModels(search);
            if (models.isEmpty()) {
                return "No image-classification models found for: " + search;
            }
            return models.stream()
                    .limit(15)
                    .map(m -> m.modelId() + " (library: " + m.libraryName() + ", tags: " + String.join(", ", m.tags().stream().limit(5).toList()) + ")")
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            return "Search failed: " + e.getMessage();
        }
    }

    @Tool(description = "Classify an image using a Hugging Face model. If the model is ONNX (e.g. suko/nsfw), it is downloaded and run locally. Use for tasks like 'is this image censored/NSFW?' — pass the image path and model ID (e.g. suko/nsfw).")
    public String classifyImageWithHf(
            @ToolParam(description = "Full path to the image file") String imagePath,
            @ToolParam(description = "Hugging Face model ID, e.g. suko/nsfw for NSFW detection") String modelId) {
        notifier.notify("Classifying image with " + modelId + "...");
        try {
            Path imgPath = Paths.get(imagePath).normalize().toAbsolutePath();
            if (!Files.isRegularFile(imgPath)) {
                return "Image file not found: " + imagePath;
            }
            List<String> paths = hfService.getModelFilePaths(modelId);
            if (!paths.contains("model.onnx")) {
                return "Model " + modelId + " is not available as ONNX (no model.onnx). Use searchHuggingFaceImageModels to find ONNX models, or use Hugging Face Inference API in the cloud.";
            }
            Path modelDir = hfService.ensureOnnxModelCached(modelId);
            String result = classifier.classify(imgPath, modelDir);
            return "Result: " + result;
        } catch (Exception e) {
            return "Classification failed: " + e.getMessage();
        }
    }
}
