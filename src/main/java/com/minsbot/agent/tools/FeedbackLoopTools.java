package com.minsbot.agent.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Feedback loop: tracks which bot suggestions the user acts on vs. dismisses.
 * Learns over time which types of help are useful and adjusts confidence.
 * Persisted to ~/mins_bot_data/feedback.json.
 */
@Component
public class FeedbackLoopTools {

    private static final Logger log = LoggerFactory.getLogger(FeedbackLoopTools.class);
    private static final Path FEEDBACK_FILE = Paths.get(
            System.getProperty("user.home"), "mins_bot_data", "feedback.json");
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_ENTRIES = 1000;

    private final ToolExecutionNotifier notifier;
    private final AtomicLong idGen = new AtomicLong(1);
    private final List<Map<String, Object>> feedback = new CopyOnWriteArrayList<>();

    public FeedbackLoopTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @PostConstruct
    public void init() {
        if (Files.exists(FEEDBACK_FILE)) {
            try {
                feedback.addAll(mapper.readValue(FEEDBACK_FILE.toFile(), new TypeReference<>() {}));
                long maxId = feedback.stream()
                        .mapToLong(f -> ((Number) f.getOrDefault("id", 0)).longValue())
                        .max().orElse(0);
                idGen.set(maxId + 1);
                log.info("[Feedback] Loaded {} entries", feedback.size());
            } catch (IOException e) { log.warn("[Feedback] Load failed: {}", e.getMessage()); }
        }
    }

    /** Record a suggestion made by the bot (called internally by auto-pilot, proactive engine, etc.). */
    public long recordSuggestion(String suggestion, String category, String context) {
        long id = idGen.getAndIncrement();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", id);
        entry.put("suggestion", suggestion);
        entry.put("category", category);
        entry.put("context", context);
        entry.put("timestamp", System.currentTimeMillis());
        entry.put("outcome", "pending"); // pending, accepted, dismissed, ignored
        entry.put("rating", 0); // 0 = unrated, 1-5
        feedback.add(entry);
        while (feedback.size() > MAX_ENTRIES) feedback.remove(0);
        save();
        return id;
    }

    /** Mark a suggestion as accepted (user acted on it). */
    public void markAccepted(long id) {
        findById(id).ifPresent(f -> { f.put("outcome", "accepted"); save(); });
    }

    /** Mark a suggestion as dismissed (user explicitly said no). */
    public void markDismissed(long id) {
        findById(id).ifPresent(f -> { f.put("outcome", "dismissed"); save(); });
    }

    // ═══ AI-callable tools ═══

    @Tool(description = "Rate a previous bot suggestion. Use when the user says "
            + "'that was helpful', 'good suggestion', 'bad idea', 'don't suggest that again'.")
    public String rateSuggestion(
            @ToolParam(description = "Rating 1-5 (1=terrible, 5=great)") double rating,
            @ToolParam(description = "Which suggestion to rate: 'last', or a brief description to match") String which) {
        int r = Math.max(1, Math.min(5, (int) rating));
        notifier.notify("Recording feedback...");

        Map<String, Object> target = null;
        if ("last".equalsIgnoreCase(which.trim())) {
            if (!feedback.isEmpty()) target = feedback.get(feedback.size() - 1);
        } else {
            String lower = which.toLowerCase();
            for (int i = feedback.size() - 1; i >= 0; i--) {
                String sug = ((String) feedback.get(i).getOrDefault("suggestion", "")).toLowerCase();
                if (sug.contains(lower)) { target = feedback.get(i); break; }
            }
        }
        if (target == null) return "Couldn't find that suggestion in my history.";
        target.put("rating", r);
        target.put("outcome", r >= 3 ? "accepted" : "dismissed");
        save();
        return "Got it — rated " + (r >= 4 ? "positively" : r >= 3 ? "neutral" : "negatively") + ". I'll adjust my suggestions accordingly.";
    }

    @Tool(description = "Show feedback statistics: acceptance rate, top categories, what works and what doesn't. "
            + "Use when the user asks 'how are your suggestions?', 'feedback stats', 'are you learning?'.")
    public String feedbackStats() {
        notifier.notify("Analyzing feedback...");
        if (feedback.isEmpty()) return "No feedback data yet. I'll track my suggestions over time.";

        long total = feedback.size();
        long accepted = feedback.stream().filter(f -> "accepted".equals(f.get("outcome"))).count();
        long dismissed = feedback.stream().filter(f -> "dismissed".equals(f.get("outcome"))).count();
        long pending = feedback.stream().filter(f -> "pending".equals(f.get("outcome"))).count();

        double acceptRate = total > 0 ? (double) accepted / (accepted + dismissed) * 100 : 0;

        // Average rating
        double avgRating = feedback.stream()
                .mapToInt(f -> ((Number) f.getOrDefault("rating", 0)).intValue())
                .filter(r -> r > 0)
                .average().orElse(0);

        // Category breakdown
        Map<String, long[]> categoryStats = new LinkedHashMap<>(); // [accepted, dismissed]
        for (Map<String, Object> f : feedback) {
            String cat = (String) f.getOrDefault("category", "general");
            String outcome = (String) f.getOrDefault("outcome", "pending");
            long[] counts = categoryStats.computeIfAbsent(cat, k -> new long[2]);
            if ("accepted".equals(outcome)) counts[0]++;
            else if ("dismissed".equals(outcome)) counts[1]++;
        }

        StringBuilder sb = new StringBuilder("Feedback Loop Stats:\n\n");
        sb.append("  Total suggestions tracked: ").append(total).append("\n");
        sb.append("  Accepted: ").append(accepted).append(" | Dismissed: ").append(dismissed)
                .append(" | Pending: ").append(pending).append("\n");
        sb.append("  Acceptance rate: ").append(String.format("%.0f%%", acceptRate)).append("\n");
        sb.append("  Average rating: ").append(avgRating > 0 ? String.format("%.1f/5", avgRating) : "no ratings yet").append("\n\n");

        if (!categoryStats.isEmpty()) {
            sb.append("  By category:\n");
            categoryStats.forEach((cat, counts) -> {
                long catTotal = counts[0] + counts[1];
                double catRate = catTotal > 0 ? (double) counts[0] / catTotal * 100 : 0;
                sb.append("    • ").append(cat).append(": ")
                        .append(String.format("%.0f%% accepted", catRate))
                        .append(" (").append(counts[0]).append("/").append(catTotal).append(")\n");
            });
        }

        // Insights
        sb.append("\n  Insights:\n");
        if (acceptRate > 70) sb.append("    ✓ Most suggestions are well-received.\n");
        else if (acceptRate < 30 && accepted + dismissed > 5)
            sb.append("    ⚠ Low acceptance rate — I should be more selective with suggestions.\n");

        return sb.toString();
    }

    @Tool(description = "Show recent suggestion history with outcomes.")
    public String recentFeedback(
            @ToolParam(description = "Number of recent entries to show (1-20)") double count) {
        int n = Math.max(1, Math.min(20, (int) count));
        if (feedback.isEmpty()) return "No feedback history yet.";

        int start = Math.max(0, feedback.size() - n);
        StringBuilder sb = new StringBuilder("Recent Suggestions:\n\n");
        for (int i = start; i < feedback.size(); i++) {
            Map<String, Object> f = feedback.get(i);
            String icon = switch ((String) f.getOrDefault("outcome", "pending")) {
                case "accepted" -> "✓";
                case "dismissed" -> "✗";
                default -> "○";
            };
            int rating = ((Number) f.getOrDefault("rating", 0)).intValue();
            sb.append("  ").append(icon).append(" ").append(f.get("suggestion"));
            if (rating > 0) sb.append(" [").append(rating).append("/5]");
            sb.append("\n");
        }
        return sb.toString();
    }

    /** Get acceptance rate for a category (used by other services to decide whether to suggest). */
    public double getAcceptanceRate(String category) {
        long accepted = feedback.stream()
                .filter(f -> category.equals(f.get("category")) && "accepted".equals(f.get("outcome")))
                .count();
        long total = feedback.stream()
                .filter(f -> category.equals(f.get("category")) && !"pending".equals(f.get("outcome")))
                .count();
        return total > 0 ? (double) accepted / total : 0.5; // default 50% if no data
    }

    // ═══ Internals ═══

    private Optional<Map<String, Object>> findById(long id) {
        return feedback.stream().filter(f -> ((Number) f.get("id")).longValue() == id).findFirst();
    }

    private void save() {
        try {
            Files.createDirectories(FEEDBACK_FILE.getParent());
            mapper.writeValue(FEEDBACK_FILE.toFile(), feedback);
        } catch (IOException e) { log.error("[Feedback] Save failed: {}", e.getMessage()); }
    }
}
