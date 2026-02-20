package com.minsbot.huggingface;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Calls Hugging Face Hub API to list models and download files. Used by HuggingFaceImageTool
 * to discover image-classification models and cache ONNX models for local inference.
 */
@Service
public class HuggingFaceService {

    private static final Logger log = LoggerFactory.getLogger(HuggingFaceService.class);
    private static final String API_BASE = "https://huggingface.co/api";
    private static final String RESOLVE_BASE = "https://huggingface.co";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    @Value("${app.huggingface.cache-dir:${user.home}/.cache/mins_bot/hf_models}")
    private String cacheDir;

    /**
     * List public image-classification models, optionally filtered by search term (e.g. "nsfw", "censored").
     * Returns model IDs and basic metadata.
     */
    public List<HfModelInfo> listImageClassificationModels(String search) throws IOException {
        String q = "?pipeline_tag=image-classification&limit=20";
        if (search != null && !search.isBlank()) {
            q += "&search=" + java.net.URLEncoder.encode(search.trim(), java.nio.charset.StandardCharsets.UTF_8);
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + "/models" + q))
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
        if (response.statusCode() != 200) {
            throw new IOException("HF API returned " + response.statusCode());
        }
        JsonNode root = objectMapper.readTree(response.body());
        List<HfModelInfo> out = new ArrayList<>();
        for (JsonNode node : root) {
            String id = node.has("modelId") ? node.path("modelId").asText() : node.path("id").asText();
            String pipeline = node.has("pipeline_tag") ? node.path("pipeline_tag").asText("") : "";
            String library = node.has("library_name") ? node.path("library_name").asText("") : "";
            List<String> tags = new ArrayList<>();
            if (node.has("tags") && node.get("tags").isArray()) {
                node.get("tags").forEach(t -> tags.add(t.asText()));
            }
            out.add(new HfModelInfo(id, pipeline, library, tags));
        }
        return out;
    }

    /**
     * Get file tree for a model repo (main branch).
     */
    public List<String> getModelFilePaths(String modelId) throws IOException {
        String url = API_BASE + "/models/" + modelId + "/tree/main";
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
        if (response.statusCode() != 200) {
            throw new IOException("HF API tree returned " + response.statusCode() + " for " + modelId);
        }
        JsonNode root = objectMapper.readTree(response.body());
        List<String> paths = new ArrayList<>();
        for (JsonNode node : root) {
            if (node.has("path")) {
                paths.add(node.path("path").asText());
            }
        }
        return paths;
    }

    /**
     * Download a file from a model repo to the cache. Returns the local path. Skips download if already present.
     */
    public Path downloadToCache(String modelId, String filePath) throws IOException {
        Path cacheRoot = Paths.get(cacheDir).toAbsolutePath();
        Path localDir = cacheRoot.resolve(modelId.replace("/", "_"));
        Path localFile = localDir.resolve(filePath);
        if (Files.exists(localFile) && Files.size(localFile) > 0) {
            log.debug("Using cached {} {}", modelId, filePath);
            return localFile;
        }
        Files.createDirectories(localDir);
        String url = RESOLVE_BASE + "/" + modelId.replace("/", "/") + "/resolve/main/" + filePath;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
        if (response.statusCode() != 200) {
            throw new IOException("Download returned " + response.statusCode() + " for " + url);
        }
        Files.copy(response.body(), localFile);
        log.info("Downloaded {} to {}", url, localFile);
        return localFile;
    }

    /**
     * Ensure an ONNX model (model.onnx) and optional signature.json are in cache. Returns the directory containing model.onnx.
     */
    public Path ensureOnnxModelCached(String modelId) throws IOException {
        List<String> paths = getModelFilePaths(modelId);
        if (!paths.contains("model.onnx")) {
            throw new IOException("Model " + modelId + " has no model.onnx (not an ONNX model?). Available: " + paths);
        }
        downloadToCache(modelId, "model.onnx");
        if (paths.contains("signature.json")) {
            downloadToCache(modelId, "signature.json");
        }
        if (paths.contains("labels.txt")) {
            downloadToCache(modelId, "labels.txt");
        }
        return Paths.get(cacheDir).toAbsolutePath().resolve(modelId.replace("/", "_"));
    }

    public String getCacheDir() {
        return cacheDir;
    }

    public record HfModelInfo(String modelId, String pipelineTag, String libraryName, List<String> tags) {}
}
