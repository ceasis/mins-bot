package com.minsbot.skills.filesearch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Semantic search over the user's local files. Builds an embedding index for
 * chosen folders (Documents, Downloads, etc.), persists it to
 * {@code ~/mins_bot_data/mins_semantic_index.json}, and answers natural-language
 * queries with a cosine-similarity ranked list of matching files.
 */
@Service
public class SemanticFileSearchService {

    private static final Logger log = LoggerFactory.getLogger(SemanticFileSearchService.class);
    private static final Path INDEX_FILE = Paths.get(System.getProperty("user.home"),
            "mins_bot_data", "mins_semantic_index.json");
    private static final long MAX_FILE_BYTES = 2 * 1024 * 1024;  // skip huge files
    private static final int CHUNK_CHARS = 1800;                 // ~400 tokens
    private static final String EMBED_MODEL = "text-embedding-3-small";

    @Value("${spring.ai.openai.api-key:}")
    private String openAiKey;

    @Value("${spring.ai.openai.base-url:https://api.openai.com}")
    private String openAiBase;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    /** In-memory copy of the persisted index. */
    private final List<IndexEntry> index = new ArrayList<>();
    private final AtomicBoolean indexing = new AtomicBoolean(false);

    @PostConstruct
    public void loadIndex() {
        try {
            if (!Files.isRegularFile(INDEX_FILE)) return;
            List<IndexEntry> loaded = mapper.readValue(INDEX_FILE.toFile(),
                    new TypeReference<List<IndexEntry>>() {});
            synchronized (index) { index.clear(); index.addAll(loaded); }
            log.info("[FileSearch] Loaded {} indexed chunks", index.size());
        } catch (Exception e) {
            log.warn("[FileSearch] Could not load index: {}", e.getMessage());
        }
    }

    @Tool(description = "Build a semantic search index over all text files in a folder (recursive). "
            + "Use when the user says 'index my Documents folder', 'build file index', 'enable semantic search on my Downloads'. "
            + "Reads .txt .md .json .csv .log and extracted text from PDFs/docs already in the folder, chunks them, "
            + "generates embeddings via OpenAI, and saves the index. Safe to re-run — it appends and deduplicates.")
    public String indexFolder(
            @ToolParam(description = "Absolute folder path to index recursively") String folderPath) {
        if (openAiKey == null || openAiKey.isBlank()) {
            return "OpenAI key not set — add spring.ai.openai.api-key in Setup.";
        }
        if (!indexing.compareAndSet(false, true)) {
            return "Indexing already in progress — wait for it to finish.";
        }
        try {
            Path root = Paths.get(folderPath.trim());
            if (!Files.isDirectory(root)) return "Not a directory: " + folderPath;

            List<Path> files = new ArrayList<>();
            Files.walk(root)
                    .filter(Files::isRegularFile)
                    .filter(this::isTextLike)
                    .filter(p -> { try { return Files.size(p) <= MAX_FILE_BYTES; } catch (IOException e) { return false; } })
                    .forEach(files::add);

            int added = 0, skipped = 0;
            for (Path p : files) {
                try {
                    String text = Files.readString(p, StandardCharsets.UTF_8);
                    if (text.isBlank()) { skipped++; continue; }
                    String sig = p.toAbsolutePath() + "|" + Files.getLastModifiedTime(p).toMillis();
                    // De-dupe: skip if any chunk with this signature already exists
                    synchronized (index) {
                        boolean already = index.stream().anyMatch(e -> sig.equals(e.signature));
                        if (already) { skipped++; continue; }
                    }
                    for (int off = 0; off < text.length(); off += CHUNK_CHARS) {
                        String chunk = text.substring(off, Math.min(text.length(), off + CHUNK_CHARS));
                        float[] vec = embed(chunk);
                        if (vec == null) continue;
                        IndexEntry ie = new IndexEntry();
                        ie.path = p.toAbsolutePath().toString();
                        ie.signature = sig;
                        ie.chunkOffset = off;
                        ie.text = chunk;
                        ie.vector = vec;
                        synchronized (index) { index.add(ie); }
                        added++;
                    }
                } catch (Exception e) {
                    log.debug("[FileSearch] skip {}: {}", p, e.getMessage());
                    skipped++;
                }
            }
            saveIndex();
            return "Indexed " + added + " chunks from " + files.size() + " files (" + skipped + " skipped). "
                    + "Total entries in index: " + index.size() + ".";
        } catch (Exception e) {
            log.warn("[FileSearch] indexFolder failed: {}", e.getMessage());
            return "Indexing failed: " + e.getMessage();
        } finally {
            indexing.set(false);
        }
    }

    @Tool(description = "Search indexed files by meaning, not filename. Returns the top N matching files "
            + "with a short snippet from each. Use when the user says 'where did I put that contract', "
            + "'find my notes about X', 'search my files for Y', 'semantic file search', 'find that doc where I wrote about Z'.")
    public String searchMyFiles(
            @ToolParam(description = "Natural-language search query") String query,
            @ToolParam(description = "Number of top results to return (1-10, default 5)") Integer topK) {
        if (query == null || query.isBlank()) return "Please provide a search query.";
        if (index.isEmpty()) return "Index is empty. Run indexFolder on a folder first.";
        if (openAiKey == null || openAiKey.isBlank()) return "OpenAI key not set.";

        int k = Math.max(1, Math.min(10, topK != null ? topK : 5));
        float[] q = embed(query.trim());
        if (q == null) return "Embedding failed.";

        List<Scored> scored;
        synchronized (index) {
            scored = new ArrayList<>(index.size());
            for (IndexEntry e : index) scored.add(new Scored(e, cosine(q, e.vector)));
        }
        scored.sort(Comparator.comparingDouble((Scored s) -> s.score).reversed());

        // Dedup by file path — keep best chunk per file
        java.util.LinkedHashMap<String, Scored> byPath = new java.util.LinkedHashMap<>();
        for (Scored s : scored) byPath.putIfAbsent(s.entry.path, s);

        StringBuilder sb = new StringBuilder("🔎 Top " + k + " matches for \"" + query + "\":\n\n");
        int i = 0;
        for (Scored s : byPath.values()) {
            if (i++ >= k) break;
            String snip = s.entry.text.replaceAll("\\s+", " ").trim();
            if (snip.length() > 200) snip = snip.substring(0, 200) + "…";
            sb.append(i).append(". ").append(s.entry.path)
              .append("\n   score: ").append(String.format("%.3f", s.score))
              .append("\n   …").append(snip).append("\n\n");
        }
        return sb.toString().trim();
    }

    @Tool(description = "Show stats about the semantic file-search index: entry count, unique files, size on disk.")
    public String getIndexStats() {
        int entries = index.size();
        long uniqueFiles = index.stream().map(e -> e.path).distinct().count();
        long diskBytes = Files.isRegularFile(INDEX_FILE)
                ? safeSize() : 0L;
        return "Semantic file-search index:\n"
                + "• entries: " + entries + "\n"
                + "• unique files: " + uniqueFiles + "\n"
                + "• on-disk size: " + (diskBytes / 1024) + " KB\n"
                + "• path: " + INDEX_FILE;
    }

    @Tool(description = "Wipe the semantic file-search index. Use when the user says 'clear the file index', 'reset my file search'.")
    public String clearIndex() {
        synchronized (index) { index.clear(); }
        try { Files.deleteIfExists(INDEX_FILE); } catch (IOException ignored) {}
        return "Index cleared.";
    }

    // ─── Internals ───

    private boolean isTextLike(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        return n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".json")
                || n.endsWith(".csv") || n.endsWith(".log") || n.endsWith(".xml")
                || n.endsWith(".html") || n.endsWith(".yml") || n.endsWith(".yaml")
                || n.endsWith(".java") || n.endsWith(".py") || n.endsWith(".js")
                || n.endsWith(".ts") || n.endsWith(".go") || n.endsWith(".rs");
    }

    @SuppressWarnings("unchecked")
    private float[] embed(String text) {
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "model", EMBED_MODEL,
                    "input", text));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(openAiBase + "/v1/embeddings"))
                    .header("Authorization", "Bearer " + openAiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(30))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("[FileSearch] embed HTTP {}: {}", resp.statusCode(),
                        resp.body().length() > 200 ? resp.body().substring(0, 200) : resp.body());
                return null;
            }
            java.util.List<java.util.Map<String, Object>> data =
                    (java.util.List<java.util.Map<String, Object>>) mapper.readValue(resp.body(), Map.class).get("data");
            if (data == null || data.isEmpty()) return null;
            java.util.List<Number> arr = (java.util.List<Number>) data.get(0).get("embedding");
            float[] v = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++) v[i] = arr.get(i).floatValue();
            return v;
        } catch (Exception e) {
            log.debug("[FileSearch] embed failed: {}", e.getMessage());
            return null;
        }
    }

    private static double cosine(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        double d = Math.sqrt(na) * Math.sqrt(nb);
        return d == 0 ? 0 : dot / d;
    }

    private long safeSize() {
        try { return Files.size(INDEX_FILE); } catch (IOException e) { return 0; }
    }

    private synchronized void saveIndex() {
        try {
            Files.createDirectories(INDEX_FILE.getParent());
            mapper.writeValue(INDEX_FILE.toFile(), index);
        } catch (Exception e) {
            log.warn("[FileSearch] saveIndex failed: {}", e.getMessage());
        }
    }

    private record Scored(IndexEntry entry, double score) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IndexEntry {
        public String path;
        public String signature;
        public int chunkOffset;
        public String text;
        public float[] vector;
    }
}
