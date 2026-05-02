package com.minsbot.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Implicit preference learning across deliverables. Every completed deliverable
 * lands here as a {@link Precedent} — the goal text, format/output choice, and
 * the set of <em>signals</em> the final draft actually contained (prices, links,
 * images, comparison tables, recommendations, rankings, specs).
 *
 * <p>On the next deliverable, {@link #findRelevant} returns the most similar
 * past precedents (by token Jaccard on the goal text). The executor injects
 * those into the planner's prompt so the model sees, in plain English:
 * <em>"Last time the user asked for a PDF report on cars, the result included
 * prices, images, and links. Plan accordingly."</em>
 *
 * <p>Storage is a single JSONL file at
 * {@code ~/mins_bot_data/deliverable_precedents/log.jsonl} — append-only, one
 * record per line. Read on startup into a small in-memory list (newest first,
 * capped at 200 entries) so retrieval is O(N·tokens) without disk I/O.
 *
 * <p>Heuristic, not embeddings. Phase 2 can swap in semantic similarity when
 * a wedge actually demands it.
 */
@Service
public class DeliverablePrecedentStore {

    private static final Logger log = LoggerFactory.getLogger(DeliverablePrecedentStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Path DIR = Paths.get(
            System.getProperty("user.home"), "mins_bot_data", "deliverable_precedents");
    private static final Path LOG_FILE = DIR.resolve("log.jsonl");

    /** Cap on in-memory precedents. Old ones still live on disk; only retrieval is bounded. */
    private static final int IN_MEMORY_CAP = 200;
    /** Top-K precedents returned by {@link #findRelevant}. */
    private static final int RETRIEVE_K = 3;
    /** Minimum Jaccard similarity to count as "relevant" (0..1). Below this, the precedent
     *  is too unrelated to bother showing — better to plan from scratch than mislead. */
    private static final double MIN_SIMILARITY = 0.10;

    /** Stop words — high-frequency tokens that don't carry topic signal. */
    private static final Set<String> STOP = Set.of(
            "a", "an", "and", "or", "of", "the", "for", "with", "to", "in",
            "on", "at", "by", "from", "as", "is", "are", "be", "this", "that",
            "i", "me", "my", "we", "our", "you", "your", "it", "its",
            "report", "deliverable", "doc", "document", "file"
    );

    private final List<Precedent> recent = Collections.synchronizedList(new ArrayList<>());

    /** Optional embedding service. When present, retrieval uses cosine similarity
     *  on embeddings; without it, falls back to token Jaccard. Field-injected so
     *  bot-side init order doesn't constrain construction. */
    @Autowired(required = false)
    private EmbeddingService embedder;

    public DeliverablePrecedentStore() {
        try {
            Files.createDirectories(DIR);
            loadFromDisk();
            log.info("[Precedents] loaded {} prior deliverable(s) from {}", recent.size(), LOG_FILE);
        } catch (Exception e) {
            log.warn("[Precedents] init failed: {}", e.getMessage());
        }
    }

    // ─── Public API ──────────────────────────────────────────────────────

    /**
     * Record a completed deliverable. Signals are extracted from the final
     * markdown; the executor only needs to pass the body.
     */
    public void record(String goal, String format, String output, String finalMarkdown) {
        if (goal == null || goal.isBlank()) return;
        Set<String> signals = extractSignals(finalMarkdown == null ? "" : finalMarkdown);
        // Compute embedding from goal text only — the goal is what we'll match
        // against, and embedding the entire scratchpad would be cost-prohibitive
        // for marginal gain. Failure → null → Jaccard fallback at retrieval time.
        float[] vec = (embedder != null && embedder.isAvailable()) ? embedder.embed(goal.trim()) : null;
        Precedent p = new Precedent(
                Instant.now().toString(),
                goal.trim(),
                format == null ? "report" : format.trim(),
                output == null ? "md" : output.trim(),
                signals,
                vec);
        synchronized (recent) {
            recent.add(0, p);
            while (recent.size() > IN_MEMORY_CAP) {
                recent.remove(recent.size() - 1);
            }
        }
        appendToDisk(p);
    }

    /**
     * Find precedents most similar to {@code goal}. Returns up to
     * {@link #RETRIEVE_K} entries, sorted by similarity descending,
     * filtered to those above {@link #MIN_SIMILARITY}.
     */
    public List<Precedent> findRelevant(String goal) {
        if (goal == null || goal.isBlank()) return List.of();

        // Prefer embedding-based cosine when both the query and at least some
        // precedents have vectors. The embedding catches semantic similarity
        // that Jaccard misses ("wedding gifts" ↔ "anniversary presents").
        float[] qVec = (embedder != null && embedder.isAvailable()) ? embedder.embed(goal) : null;
        Set<String> qTokens = tokenize(goal);
        if (qVec == null && qTokens.isEmpty()) return List.of();

        List<Scored> scored = new ArrayList<>();
        synchronized (recent) {
            for (Precedent p : recent) {
                double sim;
                if (qVec != null && p.embedding != null) {
                    // Cosine in [-1, 1]; remap to [0, 1] for consistent thresholding.
                    double cos = EmbeddingService.cosine(qVec, p.embedding);
                    sim = (cos + 1.0) / 2.0;
                } else {
                    sim = jaccard(qTokens, tokenize(p.goal));
                }
                if (sim >= effectiveThreshold(qVec != null && p.embedding != null)) {
                    scored.add(new Scored(p, sim));
                }
            }
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        List<Precedent> out = new ArrayList<>(RETRIEVE_K);
        for (int i = 0; i < Math.min(RETRIEVE_K, scored.size()); i++) out.add(scored.get(i).p);
        return out;
    }

    /** Cosine and Jaccard live on different scales — cosine on text-embedding-3-small
     *  rarely drops below ~0.3 for any English text pair, while Jaccard easily hits 0.
     *  Use a higher floor for cosine so we don't return weak matches. */
    private static double effectiveThreshold(boolean usingCosine) {
        return usingCosine ? 0.62 : MIN_SIMILARITY;
    }

    /**
     * Plain-text summary of relevant past deliverables suitable for injection
     * into a planner's system prompt. Empty string if no precedents match.
     */
    public String relevantPrecedentsAsContext(String goal) {
        List<Precedent> ps = findRelevant(goal);
        if (ps.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("Past similar deliverables this user asked for (use as a hint about what they "
                + "tend to want — only include these elements if they fit the current goal):\n");
        for (Precedent p : ps) {
            String when = humanizeAge(p.timestamp);
            sb.append("  • ").append(when).append(": \"").append(truncate(p.goal, 80)).append("\"")
              .append(" → ").append(p.format).append("/").append(p.output);
            if (!p.signals.isEmpty()) {
                sb.append(" — included: ").append(String.join(", ", p.signals));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    public int size() {
        synchronized (recent) { return recent.size(); }
    }

    /** The single most recent precedent, or {@code null} if none. Used by the
     *  feedback store to attribute "I liked it" / "you forgot X" to the right
     *  deliverable. */
    public Precedent latest() {
        synchronized (recent) {
            return recent.isEmpty() ? null : recent.get(0);
        }
    }

    /** Snapshot of the most-recent N precedents (newest first). */
    public List<Precedent> listRecent(int limit) {
        int n = Math.max(1, Math.min(limit, IN_MEMORY_CAP));
        synchronized (recent) {
            return new ArrayList<>(recent.subList(0, Math.min(n, recent.size())));
        }
    }

    public void clear() {
        synchronized (recent) { recent.clear(); }
        try { Files.deleteIfExists(LOG_FILE); } catch (Exception ignored) {}
    }

    // ─── Signal extraction ──────────────────────────────────────────────

    /**
     * Heuristic detection of structural / content signals in the final markdown.
     * Each signal is a short noun phrase the planner can recognize.
     */
    public static Set<String> extractSignals(String md) {
        Set<String> out = new LinkedHashSet<>();
        if (md == null || md.isBlank()) return out;
        String low = md.toLowerCase();

        // Prices: $1,234 / $12.99 / "starts at $X" / "from $X"
        if (Pattern.compile("\\$\\d[\\d,]*(?:\\.\\d{1,2})?").matcher(md).find()
                || low.contains("price:") || low.contains("starting at")
                || low.contains("from $") || low.contains("msrp")) {
            out.add("prices");
        }
        // Links — markdown [text](http...) or bare http(s)://...
        if (Pattern.compile("\\[[^\\]]+\\]\\(https?://").matcher(md).find()
                || Pattern.compile("(?<![(\\w])https?://").matcher(md).find()) {
            out.add("links");
        }
        // Images — markdown ![alt](...) or <img tags
        if (Pattern.compile("!\\[[^\\]]*\\]\\([^)]+\\)").matcher(md).find()
                || low.contains("<img ")) {
            out.add("images");
        }
        // Comparison table — explicit "comparison" header or markdown table syntax with >=2 rows
        if (low.contains("## comparison") || low.contains("# comparison")
                || hasMarkdownTable(md)) {
            out.add("comparison-table");
        }
        // Recommendation / verdict
        if (low.contains("recommendation:") || low.contains("## recommendation")
                || low.contains("our pick") || low.contains("verdict:")
                || low.contains("bottom line")) {
            out.add("recommendation");
        }
        // Ranking — numbered list of items at top level (1. ... 2. ...) or "Top N"
        if (Pattern.compile("(?m)^\\s*1\\.\\s+\\S").matcher(md).find()
                && Pattern.compile("(?m)^\\s*2\\.\\s+\\S").matcher(md).find()
                || Pattern.compile("(?i)\\btop\\s+\\d+\\b").matcher(md).find()) {
            out.add("ranking");
        }
        // Specs — common spec patterns: GB / GHz / mAh / inches / mm
        if (Pattern.compile("(?i)\\b\\d+\\s?(gb|tb|mb|ghz|mhz|mah|wh|kg|lb|inch|\\\"|mm|cm)\\b").matcher(md).find()) {
            out.add("specs");
        }
        // Pros / cons
        if ((low.contains("pros:") || low.contains("## pros")) && (low.contains("cons:") || low.contains("## cons"))) {
            out.add("pros-cons");
        }
        return out;
    }

    private static boolean hasMarkdownTable(String md) {
        // Pipe-table heuristic: at least one line with 2+ pipes followed by a separator row.
        Matcher m = Pattern.compile("(?m)^\\s*\\|.*\\|.*$\\n\\s*\\|[\\s:|-]+\\|").matcher(md);
        return m.find();
    }

    // ─── Similarity ─────────────────────────────────────────────────────

    private static Set<String> tokenize(String text) {
        if (text == null) return Set.of();
        Set<String> out = new HashSet<>();
        for (String w : text.toLowerCase().split("[^a-z0-9]+")) {
            if (w.length() < 3) continue;
            if (STOP.contains(w)) continue;
            out.add(w);
        }
        return out;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int inter = 0;
        for (String s : a) if (b.contains(s)) inter++;
        int union = a.size() + b.size() - inter;
        return union == 0 ? 0 : (double) inter / union;
    }

    // ─── Persistence ────────────────────────────────────────────────────

    private void loadFromDisk() {
        if (!Files.isRegularFile(LOG_FILE)) return;
        try {
            List<String> lines = Files.readAllLines(LOG_FILE, StandardCharsets.UTF_8);
            // Newest last in file (append-only); we want newest-first in memory.
            for (int i = lines.size() - 1; i >= 0 && recent.size() < IN_MEMORY_CAP; i--) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;
                try {
                    JsonNode n = JSON.readTree(line);
                    Set<String> sigs = new LinkedHashSet<>();
                    JsonNode sa = n.path("signals");
                    if (sa.isArray()) for (JsonNode s : sa) sigs.add(s.asText());
                    float[] emb = decodeEmbedding(n.path("embedding").asText(""));
                    recent.add(new Precedent(
                            n.path("timestamp").asText(""),
                            n.path("goal").asText(""),
                            n.path("format").asText("report"),
                            n.path("output").asText("md"),
                            sigs,
                            emb));
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            log.warn("[Precedents] load failed: {}", e.getMessage());
        }
    }

    private void appendToDisk(Precedent p) {
        try {
            Map<String, Object> obj = new LinkedHashMap<>();
            obj.put("timestamp", p.timestamp);
            obj.put("goal", p.goal);
            obj.put("format", p.format);
            obj.put("output", p.output);
            obj.put("signals", p.signals);
            // Embedding stored as base64-encoded little-endian float32 — compact,
            // preserves precision, JSON-safe. Empty string when not computed.
            obj.put("embedding", encodeEmbedding(p.embedding));
            String json = JSON.writeValueAsString(obj);
            Files.writeString(LOG_FILE, json + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.debug("[Precedents] append failed: {}", e.getMessage());
        }
    }

    private static String encodeEmbedding(float[] v) {
        if (v == null || v.length == 0) return "";
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(v.length * 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (float f : v) bb.putFloat(f);
        return java.util.Base64.getEncoder().encodeToString(bb.array());
    }

    private static float[] decodeEmbedding(String b64) {
        if (b64 == null || b64.isEmpty()) return null;
        try {
            byte[] bytes = java.util.Base64.getDecoder().decode(b64);
            if (bytes.length % 4 != 0) return null;
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            float[] v = new float[bytes.length / 4];
            for (int i = 0; i < v.length; i++) v[i] = bb.getFloat();
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    // ─── Utils ──────────────────────────────────────────────────────────

    private static String humanizeAge(String iso) {
        try {
            Instant t = Instant.parse(iso);
            long secs = (Instant.now().toEpochMilli() - t.toEpochMilli()) / 1000;
            if (secs < 3600) return "just now";
            if (secs < 86400) return (secs / 3600) + "h ago";
            if (secs < 86400 * 14) return (secs / 86400) + "d ago";
            return (secs / 86400 / 7) + "w ago";
        } catch (Exception e) {
            return "earlier";
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private record Scored(Precedent p, double score) {}

    /**
     * One past deliverable. {@code embedding} is null when no embedding service
     * is configured or the call failed — retrieval falls back to Jaccard then.
     */
    public record Precedent(String timestamp, String goal, String format,
                            String output, Set<String> signals, float[] embedding) {

        /** Convenience for the legacy 5-arg shape used in tests / hand construction. */
        public Precedent(String timestamp, String goal, String format,
                         String output, Set<String> signals) {
            this(timestamp, goal, format, output, signals, null);
        }
    }
}
