package com.minsbot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single embedding service used across the bot — semantic memory, precedent
 * retrieval, future research dedup, etc. Wraps OpenAI's
 * {@code /v1/embeddings} endpoint. Adds disk-backed caching keyed by
 * SHA-256 of the (model, text) pair so identical inputs are free.
 *
 * <p>Returns {@code null} on any failure (missing key, network error, bad
 * response). Callers must treat embeddings as a quality boost, never a
 * correctness requirement — when {@code null} comes back, fall back to a
 * cheaper similarity (Jaccard, BM25, etc.).
 *
 * <p>Why a separate service rather than reusing {@code SemanticFileSearchService}
 * inline: that service owns its own index format and chunk pipeline. This one
 * is a stateless utility — give text, get a vector. Keeps consumers decoupled.
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DEFAULT_MODEL = "text-embedding-3-small"; // cheap + fast + 1536 dims
    private static final int MAX_INPUT_CHARS = 6000;                     // stay well under the 8k token limit

    @Value("${spring.ai.openai.api-key:}")
    private String openAiKey;

    @Value("${spring.ai.openai.base-url:https://api.openai.com}")
    private String openAiBase;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Memory cache. Disk cache is per-key file under {@link #CACHE_DIR}. */
    private final ConcurrentHashMap<String, float[]> mem = new ConcurrentHashMap<>();

    private static final Path CACHE_DIR = Paths.get(
            System.getProperty("user.home"), "mins_bot_data", "embedding_cache");

    public EmbeddingService() {
        try { Files.createDirectories(CACHE_DIR); }
        catch (Exception e) { log.warn("[Embed] cache dir create failed: {}", e.getMessage()); }
    }

    public boolean isAvailable() {
        return openAiKey != null && !openAiKey.isBlank();
    }

    public float[] embed(String text) {
        return embed(text, DEFAULT_MODEL);
    }

    public float[] embed(String text, String model) {
        if (text == null || text.isBlank()) return null;
        if (!isAvailable()) return null;
        String norm = text.trim();
        if (norm.length() > MAX_INPUT_CHARS) norm = norm.substring(0, MAX_INPUT_CHARS);

        String key = key(model, norm);
        float[] hit = mem.get(key);
        if (hit != null) return hit;

        // Disk cache
        Path file = CACHE_DIR.resolve(key + ".f32");
        if (Files.isRegularFile(file)) {
            try {
                byte[] bytes = Files.readAllBytes(file);
                float[] v = bytesToFloats(bytes);
                if (v != null && v.length > 0) {
                    mem.put(key, v);
                    return v;
                }
            } catch (IOException ignored) {}
        }

        // Cold path — call the API
        float[] fresh = fetch(norm, model);
        if (fresh != null) {
            mem.put(key, fresh);
            try { Files.write(file, floatsToBytes(fresh)); } catch (IOException ignored) {}
        }
        return fresh;
    }

    /** Cosine similarity in [-1, 1]. Returns 0 for null/length-mismatched vectors. */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return 0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na  += a[i] * a[i];
            nb  += b[i] * b[i];
        }
        double d = Math.sqrt(na) * Math.sqrt(nb);
        return d == 0 ? 0 : dot / d;
    }

    // ─── Internals ───────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private float[] fetch(String text, String model) {
        try {
            String body = JSON.writeValueAsString(Map.of("model", model, "input", text));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(openAiBase + "/v1/embeddings"))
                    .header("Authorization", "Bearer " + openAiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(20))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.debug("[Embed] HTTP {}: {}", resp.statusCode(),
                        resp.body() == null ? "" : truncate(resp.body(), 200));
                return null;
            }
            Map<String, Object> root = JSON.readValue(resp.body(), Map.class);
            List<Map<String, Object>> data = (List<Map<String, Object>>) root.get("data");
            if (data == null || data.isEmpty()) return null;
            List<Number> arr = (List<Number>) data.get(0).get("embedding");
            if (arr == null || arr.isEmpty()) return null;
            float[] v = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++) v[i] = arr.get(i).floatValue();
            return v;
        } catch (Exception e) {
            log.debug("[Embed] fetch failed: {}", e.getMessage());
            return null;
        }
    }

    /** Stable cache key — model + normalized text → 16-char hex SHA-256 prefix. */
    private static String key(String model, String text) {
        try {
            String norm = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                    (model + "::" + norm).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", hash[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    private static byte[] floatsToBytes(float[] v) {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(v.length * 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (float f : v) bb.putFloat(f);
        return bb.array();
    }

    private static float[] bytesToFloats(byte[] bytes) {
        if (bytes == null || bytes.length % 4 != 0) return null;
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        float[] v = new float[bytes.length / 4];
        for (int i = 0; i < v.length; i++) v[i] = bb.getFloat();
        return v;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
