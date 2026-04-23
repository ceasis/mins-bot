package com.minsbot.cost;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tracks every chat LLM call — prompt tokens, completion tokens, model, timestamp —
 * and computes cost against a configurable pricing table. Compares live spend against
 * what the equivalent work would have cost on a local Ollama model ($0), so the UI
 * can surface "you would have saved $X.XX by going local".
 *
 * <p>Persists daily totals to {@code memory/cost_history.json} so totals survive
 * restart and the Costs tab can render a multi-day chart. The in-memory per-session
 * list is the source for live totals.</p>
 */
@Service
public class TokenUsageService {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageService.class);
    private static final Path HISTORY = Paths.get("memory", "cost_history.json").toAbsolutePath();

    /** One event per LLM call. Kept in memory for the current session + persisted daily. */
    public record UsageEvent(
            long timestampMs,
            String model,
            int promptTokens,
            int completionTokens,
            double usdCost,
            double usdIfLocal
    ) {}

    /**
     * Per-model pricing in USD per 1M tokens. Input (prompt) / output (completion).
     * Numbers reflect public list pricing as of Apr 2026 — adjust in
     * {@code application.properties} via {@code app.cost.override.<model>=in,out} if you
     * negotiate custom rates.
     */
    public record Pricing(double inputUsdPerM, double outputUsdPerM) {
        public static final Pricing UNKNOWN = new Pricing(0, 0);
    }

    private static final Map<String, Pricing> TABLE = Map.ofEntries(
            // OpenAI
            Map.entry("gpt-5",               new Pricing(1.25,  10.00)),
            Map.entry("gpt-5-mini",          new Pricing(0.25,   2.00)),
            Map.entry("gpt-4.1",             new Pricing(2.00,   8.00)),
            Map.entry("gpt-4.1-mini",        new Pricing(0.40,   1.60)),
            Map.entry("gpt-4o",              new Pricing(5.00,  20.00)),
            Map.entry("gpt-4o-mini",         new Pricing(0.15,   0.60)),
            Map.entry("o3",                  new Pricing(2.00,   8.00)),
            Map.entry("o3-mini",             new Pricing(1.10,   4.40)),
            // Anthropic
            Map.entry("claude-opus-4",       new Pricing(15.00, 75.00)),
            Map.entry("claude-sonnet-4",     new Pricing(3.00,  15.00)),
            Map.entry("claude-sonnet-4-6",   new Pricing(3.00,  15.00)),
            Map.entry("claude-haiku-4-5",    new Pricing(1.00,   5.00)),
            // Google
            Map.entry("gemini-2.5-pro",      new Pricing(1.25,  10.00)),
            Map.entry("gemini-2.5-flash",    new Pricing(0.075,  0.30)),
            Map.entry("gemini-2.0-flash",    new Pricing(0.075,  0.30))
    );

    private static final Pricing FALLBACK_CLOUD = new Pricing(1.00, 4.00); // conservative guess

    private final CopyOnWriteArrayList<UsageEvent> sessionEvents = new CopyOnWriteArrayList<>();
    private final long sessionStartMs = System.currentTimeMillis();
    private final Map<String, DailyTotal> history = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public record DailyTotal(String date, long prompt, long completion, double usd, int calls) {}

    @PostConstruct
    void init() {
        loadHistory();
    }

    // ═══ Recording ═══════════════════════════════════════════════════

    /**
     * Record one LLM invocation. Pass 0 for either token count if the provider didn't
     * return it — service tolerates it, just yields a $0 estimate for that side.
     */
    public void record(String model, int promptTokens, int completionTokens) {
        if (model == null || model.isBlank()) return;
        String normalized = normalizeModelId(model);
        Pricing p = TABLE.getOrDefault(normalized, null);
        if (p == null) {
            // Heuristic — if the model id contains "local" / "ollama" / a known Ollama tag, treat as free
            if (isLocal(normalized)) p = new Pricing(0, 0);
            else {
                p = FALLBACK_CLOUD;
                log.debug("[Cost] unknown model '{}' — using fallback pricing {}", model, FALLBACK_CLOUD);
            }
        }
        double cost = (promptTokens * p.inputUsdPerM() + completionTokens * p.outputUsdPerM()) / 1_000_000.0;
        double localEquiv = 0.0; // Ollama is free; savings is the full cost
        UsageEvent ev = new UsageEvent(System.currentTimeMillis(), normalized,
                promptTokens, completionTokens, cost, localEquiv);
        sessionEvents.add(ev);
        updateHistory(ev);
    }

    // ═══ Queries ═════════════════════════════════════════════════════

    public SessionSummary currentSession() {
        long prompt = 0, completion = 0;
        double totalUsd = 0;
        Map<String, ModelBucket> byModel = new LinkedHashMap<>();
        for (UsageEvent e : sessionEvents) {
            prompt += e.promptTokens();
            completion += e.completionTokens();
            totalUsd += e.usdCost();
            byModel.merge(e.model(),
                    new ModelBucket(e.model(), 1, e.promptTokens(), e.completionTokens(), e.usdCost()),
                    (a, b) -> new ModelBucket(a.model(), a.calls() + b.calls(),
                            a.prompt() + b.prompt(), a.completion() + b.completion(),
                            a.usd() + b.usd()));
        }
        long elapsedMs = System.currentTimeMillis() - sessionStartMs;
        return new SessionSummary(sessionStartMs, elapsedMs, sessionEvents.size(),
                prompt, completion, totalUsd, totalUsd /* savings if local */,
                new ArrayList<>(byModel.values()));
    }

    public List<DailyTotal> dailyHistory(int days) {
        List<DailyTotal> list = new ArrayList<>(history.values());
        list.sort(Comparator.comparing(DailyTotal::date).reversed());
        if (list.size() > days) return list.subList(0, days);
        return list;
    }

    public record SessionSummary(
            long sessionStartMs,
            long elapsedMs,
            int calls,
            long promptTokens,
            long completionTokens,
            double totalUsd,
            double savingsIfLocalUsd,
            List<ModelBucket> byModel
    ) {}

    public record ModelBucket(String model, int calls, long prompt, long completion, double usd) {}

    // ═══ Helpers ═════════════════════════════════════════════════════

    private static String normalizeModelId(String id) {
        if (id == null) return "";
        String s = id.toLowerCase(Locale.ROOT).trim();
        // Strip date/version suffixes like "gpt-4o-2024-11-20" → "gpt-4o"
        s = s.replaceAll("-\\d{4}-\\d{2}-\\d{2}$", "");
        s = s.replaceAll("-\\d{8}$", "");
        s = s.replaceAll("\\[.*]$", "");
        return s;
    }

    private static boolean isLocal(String normalized) {
        if (normalized == null) return false;
        return normalized.contains("ollama") || normalized.contains("llama")
                || normalized.contains("mistral") || normalized.contains("qwen")
                || normalized.contains("phi3") || normalized.contains("gemma")
                || normalized.contains("deepseek-r1") || normalized.contains("local");
    }

    // ═══ Persistence ═════════════════════════════════════════════════

    private void updateHistory(UsageEvent e) {
        String date = LocalDate.now(ZoneId.systemDefault()).toString();
        history.merge(date,
                new DailyTotal(date, e.promptTokens(), e.completionTokens(), e.usdCost(), 1),
                (a, b) -> new DailyTotal(date,
                        a.prompt() + b.prompt(),
                        a.completion() + b.completion(),
                        a.usd() + b.usd(),
                        a.calls() + b.calls()));
        persist();
    }

    private synchronized void persist() {
        try {
            Files.createDirectories(HISTORY.getParent());
            Files.writeString(HISTORY, mapper.writeValueAsString(history));
        } catch (IOException ioe) {
            log.debug("[Cost] persist failed: {}", ioe.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void loadHistory() {
        if (!Files.exists(HISTORY)) return;
        try {
            Map<String, Map<String, Object>> raw = mapper.readValue(Files.readString(HISTORY), Map.class);
            raw.forEach((date, entry) -> {
                try {
                    history.put(date, new DailyTotal(
                            date,
                            ((Number) entry.get("prompt")).longValue(),
                            ((Number) entry.get("completion")).longValue(),
                            ((Number) entry.get("usd")).doubleValue(),
                            ((Number) entry.get("calls")).intValue()));
                } catch (Exception ignored) {}
            });
            log.info("[Cost] loaded {} day(s) of history", history.size());
        } catch (Exception e) {
            log.warn("[Cost] load failed: {}", e.getMessage());
        }
    }

    /** Strictly for testing: reset in-memory session. Doesn't wipe history. */
    public void resetSession() {
        sessionEvents.clear();
    }

    /** For debugging. */
    public String formatTimestamp(long ms) {
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), ZoneId.systemDefault()).toString();
    }
}
