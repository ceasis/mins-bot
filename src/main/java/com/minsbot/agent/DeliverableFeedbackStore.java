package com.minsbot.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;

/**
 * Explicit feedback loop for deliverables. While {@link DeliverablePrecedentStore}
 * learns the <em>shape</em> of past deliverables, this service learns whether
 * those shapes were actually <em>liked</em> by the user, on a per-signal basis.
 *
 * <p>For each signal (prices / images / links / comparison-table / etc.) we
 * track four counters:
 * <ul>
 *   <li><b>kept</b>     — signal was in a delivered report and the user thumbs-upped it</li>
 *   <li><b>rejected</b> — signal was in a delivered report and the user thumbs-downed</li>
 *   <li><b>wanted</b>   — signal was MISSING and the user explicitly asked for it</li>
 *   <li><b>unwanted</b> — signal was PRESENT and the user explicitly didn't want it</li>
 * </ul>
 *
 * <p>The composite preference for a signal is:
 * {@code (kept + wanted) − (rejected + unwanted)}. Positive = include by
 * default. Negative = avoid by default.
 *
 * <p>Feedback always attributes to the <em>most recent</em> deliverable
 * (the bot's "last shipped"). The precedent store keeps recent[0] as the
 * newest, so we read its signals at attribution time.
 *
 * <p>Storage: single JSON file at
 * {@code ~/mins_bot_data/deliverable_feedback.json}. Atomic write (tmp + move).
 */
@Service
public class DeliverableFeedbackStore {

    private static final Logger log = LoggerFactory.getLogger(DeliverableFeedbackStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Path FILE = Paths.get(
            System.getProperty("user.home"), "mins_bot_data", "deliverable_feedback.json");

    /** Minimum encounters with a signal before we trust its preference enough to inject. */
    private static final int MIN_CONFIDENCE_COUNT = 2;

    /** Half-life for feedback decay. Events older than this contribute roughly half
     *  as much as a fresh event of the same type. Tuned for "preferences change over
     *  weeks, not minutes" — three months ago contributes ~13%. */
    private static final long HALF_LIFE_DAYS = 30;
    private static final double DECAY_LN2_PER_DAY =
            Math.log(2) / HALF_LIFE_DAYS;

    private final Map<String, Counters> signals = new LinkedHashMap<>();
    private volatile Instant lastUpdated = null;

    public DeliverableFeedbackStore() {
        load();
    }

    // ─── Public API ──────────────────────────────────────────────────────

    /** Record thumbs-up on the latest deliverable: bumps {@code kept} for every signal it included. */
    public synchronized void recordLiked(Set<String> deliveredSignals) {
        if (deliveredSignals == null) return;
        Instant now = Instant.now();
        for (String s : deliveredSignals) bump(s, now, "kept");
        persist();
    }

    /** Record thumbs-down on the latest deliverable: bumps {@code rejected} for every signal. */
    public synchronized void recordDisliked(Set<String> deliveredSignals) {
        if (deliveredSignals == null) return;
        Instant now = Instant.now();
        for (String s : deliveredSignals) bump(s, now, "rejected");
        persist();
    }

    /** User said "you forgot X" — those signals were missing and wanted. */
    public synchronized void recordMissing(Collection<String> missing) {
        if (missing == null) return;
        Instant now = Instant.now();
        for (String s : missing) {
            String n = norm(s);
            if (n != null) bump(n, now, "wanted");
        }
        persist();
    }

    /** User said "I didn't want X" — those signals were present but unwanted. */
    public synchronized void recordUnwanted(Collection<String> unwanted) {
        if (unwanted == null) return;
        Instant now = Instant.now();
        for (String s : unwanted) {
            String n = norm(s);
            if (n != null) bump(n, now, "unwanted");
        }
        persist();
    }

    /** Single bump path: decay existing values, add 1.0 to the named field, stamp now. */
    private void bump(String signal, Instant now, String field) {
        Counters c = counters(signal);
        c.decayTo(now);
        switch (field) {
            case "kept"     -> c.kept     += 1.0;
            case "rejected" -> c.rejected += 1.0;
            case "wanted"   -> c.wanted   += 1.0;
            case "unwanted" -> c.unwanted += 1.0;
            default -> {}
        }
        c.lastUpdated = now.toString();
    }

    /**
     * Compose a "user preference profile" suitable for injection into the
     * planner system prompt. Lists signals the user reliably wants
     * ({@link #score} ≥ +1 with at least {@link #MIN_CONFIDENCE_COUNT} encounters)
     * and signals to avoid (score ≤ −1).
     */
    public synchronized String preferenceProfileAsContext() {
        if (signals.isEmpty()) return "";
        Instant now = Instant.now();
        List<String> want = new ArrayList<>();
        List<String> avoid = new ArrayList<>();
        for (var e : signals.entrySet()) {
            Counters c = e.getValue();
            // Decay-on-read snapshot so dormant signals fade even without new feedback.
            Counters snap = c.copy();
            snap.decayTo(now);
            int total = snap.total();
            if (total < MIN_CONFIDENCE_COUNT) continue;
            int score = snap.score();
            if (score >= 1)      want.add(e.getKey() + " (+" + score + "/" + total + ")");
            else if (score <= -1) avoid.add(e.getKey() + " (" + score + "/" + total + ")");
        }
        if (want.isEmpty() && avoid.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("Learned preferences from this user's feedback:\n");
        if (!want.isEmpty()) {
            sb.append("  Tends to want: ").append(String.join(", ", want)).append("\n");
        }
        if (!avoid.isEmpty()) {
            sb.append("  Tends to avoid: ").append(String.join(", ", avoid)).append("\n");
        }
        return sb.toString();
    }

    /** Read-only snapshot for the management tool. Each value is decayed to NOW
     *  so the user sees current effective weights, not stale raw counts. */
    public synchronized Map<String, Counters> snapshot() {
        Map<String, Counters> out = new LinkedHashMap<>();
        Instant now = Instant.now();
        for (var e : signals.entrySet()) {
            Counters c = e.getValue().copy();
            c.decayTo(now);
            out.put(e.getKey(), c);
        }
        return out;
    }

    public synchronized int totalSignalsTracked() {
        return signals.size();
    }

    public synchronized void clear() {
        signals.clear();
        lastUpdated = null;
        try { Files.deleteIfExists(FILE); } catch (Exception ignored) {}
    }

    // ─── Internals ───────────────────────────────────────────────────────

    private Counters counters(String signal) {
        return signals.computeIfAbsent(signal, k -> new Counters());
    }

    private static String norm(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase(Locale.ROOT).replace(' ', '-').replace('_', '-');
        return t.isEmpty() ? null : t;
    }

    private void load() {
        try {
            if (!Files.isRegularFile(FILE)) return;
            JsonNode root = JSON.readTree(Files.readString(FILE, StandardCharsets.UTF_8));
            JsonNode sigs = root.path("signals");
            if (sigs.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> it = sigs.fields();
                while (it.hasNext()) {
                    var e = it.next();
                    Counters c = new Counters();
                    // asDouble() with int default works for legacy int-counter files too.
                    c.kept        = e.getValue().path("kept").asDouble(0);
                    c.rejected    = e.getValue().path("rejected").asDouble(0);
                    c.wanted      = e.getValue().path("wanted").asDouble(0);
                    c.unwanted    = e.getValue().path("unwanted").asDouble(0);
                    String stamp  = e.getValue().path("lastUpdated").asText("");
                    c.lastUpdated = stamp.isEmpty() ? null : stamp;
                    signals.put(e.getKey(), c);
                }
            }
            String ts = root.path("lastUpdated").asText("");
            if (!ts.isEmpty()) {
                try { lastUpdated = Instant.parse(ts); } catch (Exception ignored) {}
            }
            log.info("[Feedback] loaded {} signal(s) from {}", signals.size(), FILE);
        } catch (Exception e) {
            log.warn("[Feedback] load failed: {}", e.getMessage());
        }
    }

    private void persist() {
        try {
            Files.createDirectories(FILE.getParent());
            lastUpdated = Instant.now();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("lastUpdated", lastUpdated.toString());
            Map<String, Object> sigs = new LinkedHashMap<>();
            for (var e : signals.entrySet()) {
                Counters c = e.getValue();
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("kept", c.kept);
                entry.put("rejected", c.rejected);
                entry.put("wanted", c.wanted);
                entry.put("unwanted", c.unwanted);
                if (c.lastUpdated != null) entry.put("lastUpdated", c.lastUpdated);
                sigs.put(e.getKey(), entry);
            }
            payload.put("signals", sigs);
            // Atomic write: tmp + replace
            Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            Files.writeString(tmp, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(payload),
                    StandardCharsets.UTF_8);
            Files.move(tmp, FILE, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            log.debug("[Feedback] persist failed: {}", e.getMessage());
        }
    }

    /**
     * Per-signal counters with timestamp-weighted decay. Each {@code record*}
     * call first decays existing values toward zero based on elapsed time
     * since {@code lastUpdated}, then adds 1.0 for the new event. Result:
     * preferences are dominated by recent feedback; old votes fade with the
     * configured half-life.
     *
     * <p>Doubles, not ints, because decay produces fractional values.
     * {@link #total()} and {@link #score()} round/floor as appropriate.
     */
    public static final class Counters {
        public double kept;
        public double rejected;
        public double wanted;
        public double unwanted;
        /** ISO timestamp of the last bump on this signal. {@code null} until first bump. */
        public String lastUpdated;

        /** Apply exponential decay based on time since {@link #lastUpdated}. */
        void decayTo(Instant now) {
            if (lastUpdated == null) return;
            try {
                Instant t = Instant.parse(lastUpdated);
                double days = (double) (now.toEpochMilli() - t.toEpochMilli())
                        / (1000.0 * 60 * 60 * 24);
                if (days <= 0) return;
                double factor = Math.exp(-DECAY_LN2_PER_DAY * days);
                kept     *= factor;
                rejected *= factor;
                wanted   *= factor;
                unwanted *= factor;
            } catch (Exception ignored) {}
        }

        public int total() { return (int) Math.round(kept + rejected + wanted + unwanted); }
        public int score() { return (int) Math.round((kept + wanted) - (rejected + unwanted)); }

        Counters copy() {
            Counters c = new Counters();
            c.kept = kept; c.rejected = rejected; c.wanted = wanted; c.unwanted = unwanted;
            c.lastUpdated = lastUpdated;
            return c;
        }
    }
}
