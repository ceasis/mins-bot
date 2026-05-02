package com.minsbot.agent.tools;

import com.minsbot.agent.DeliverableFeedbackStore;
import com.minsbot.agent.DeliverableFeedbackStore.Counters;
import com.minsbot.agent.DeliverablePrecedentStore;
import com.minsbot.agent.DeliverablePrecedentStore.Precedent;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * AI-callable surface for the explicit feedback loop. The LLM picks
 * {@link #recordDeliverableFeedback} when it detects the user reacting to
 * a deliverable that was just produced — "perfect", "you forgot prices",
 * "I didn't need the images", etc.
 *
 * <p>Feedback always attributes to the most recent deliverable in
 * {@link DeliverablePrecedentStore}. The accumulated per-signal weights
 * then get injected into the planner system prompt by
 * {@code DeliverableExecutor}, so the bot starts including/avoiding the
 * right signals automatically on next run.
 */
@Component
public class DeliverableFeedbackTools {

    private static final Set<String> KNOWN_SIGNALS = Set.of(
            "prices", "links", "images", "comparison-table", "recommendation",
            "ranking", "specs", "pros-cons");

    private final DeliverableFeedbackStore feedback;
    private final DeliverablePrecedentStore precedents;
    private final ToolExecutionNotifier notifier;

    public DeliverableFeedbackTools(DeliverableFeedbackStore feedback,
                                    DeliverablePrecedentStore precedents,
                                    ToolExecutionNotifier notifier) {
        this.feedback = feedback;
        this.precedents = precedents;
        this.notifier = notifier;
    }

    @Tool(description = "Record the user's feedback on the most recent deliverable. The bot uses "
            + "this to learn the user's per-signal preferences (which features to include/avoid "
            + "in future similar reports). PICK THIS TOOL whenever the user reacts to a "
            + "deliverable you just produced. Map common phrasings: "
            + "'perfect / great / exactly right / love it' → liked=true; "
            + "'no good / not what I wanted / missed the mark' → liked=false; "
            + "'you forgot the X / where are the Y / needs Z' → missing=[\"x\", \"y\", \"z\"]; "
            + "'I didn't need the X / drop the Y / too much Z' → unwanted=[\"x\", \"y\", \"z\"]. "
            + "Signal names are LOWERCASE-HYPHENATED. Known signals: "
            + "prices, links, images, comparison-table, recommendation, ranking, specs, pros-cons. "
            + "Pass any combination of liked + missing + unwanted in a single call. "
            + "Returns a confirmation listing which counters were updated.")
    public String recordDeliverableFeedback(
            @ToolParam(description = "True if the user is positive about the latest deliverable, "
                    + "false if negative. Pass null/omit if user only listed missing/unwanted.") Boolean liked,
            @ToolParam(description = "Comma-separated list of signals the user said were MISSING "
                    + "(wanted but absent). Empty string if none. Use known signal names.") String missing,
            @ToolParam(description = "Comma-separated list of signals the user said were UNWANTED "
                    + "(present but not desired). Empty string if none.") String unwanted) {

        Precedent latest = precedents.latest();
        if (latest == null) {
            return "No recent deliverable to attach feedback to. Ask me to produce a report first.";
        }
        notifier.notify("📝 recording feedback…");

        StringBuilder updates = new StringBuilder();

        if (liked != null) {
            Set<String> sigs = latest.signals() == null ? Set.of() : latest.signals();
            if (Boolean.TRUE.equals(liked)) {
                feedback.recordLiked(sigs);
                updates.append("kept ✓ (").append(sigs.size()).append(" signals); ");
            } else {
                feedback.recordDisliked(sigs);
                updates.append("rejected ✗ (").append(sigs.size()).append(" signals); ");
            }
        }

        List<String> miss = parseList(missing);
        if (!miss.isEmpty()) {
            feedback.recordMissing(miss);
            updates.append("missing+: ").append(String.join(", ", miss)).append("; ");
        }

        List<String> unw = parseList(unwanted);
        if (!unw.isEmpty()) {
            feedback.recordUnwanted(unw);
            updates.append("unwanted+: ").append(String.join(", ", unw)).append("; ");
        }

        if (updates.length() == 0) {
            return "No feedback signal supplied — pass liked, missing, or unwanted.";
        }
        return "Logged feedback on \"" + truncate(latest.goal(), 60) + "\": "
                + updates.toString().trim();
    }

    @Tool(description = "Show the bot's accumulated learned preferences from explicit feedback. "
            + "Returns each signal with its score (positive = include by default, negative = avoid) "
            + "and total encounters. Use when the user asks 'what have you learned about my "
            + "preferences', 'show my feedback profile', 'what do you know I like'.")
    public String showLearnedPreferences() {
        notifier.notify("📊 learned preferences…");
        Map<String, Counters> snap = feedback.snapshot();
        if (snap.isEmpty()) {
            return "No feedback recorded yet. Once you tell me how my reports landed (\"perfect\", "
                    + "\"you forgot prices\", etc.) I'll start tuning future deliverables.";
        }
        // Sort by score descending so the strongest signals show first.
        List<Map.Entry<String, Counters>> entries = new ArrayList<>(snap.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue().score(), a.getValue().score()));

        StringBuilder sb = new StringBuilder();
        sb.append("Learned preferences (").append(snap.size()).append(" signals):\n\n");
        for (var e : entries) {
            Counters c = e.getValue();
            int s = c.score();
            String marker = s > 0 ? "▲" : (s < 0 ? "▼" : "·");
            sb.append("  ").append(marker).append(" ").append(pad(e.getKey(), 18))
              .append("score ").append(String.format("%+d", s))
              .append("  (kept ").append(c.kept)
              .append(", rejected ").append(c.rejected)
              .append(", wanted ").append(c.wanted)
              .append(", unwanted ").append(c.unwanted).append(")\n");
        }
        return sb.toString().trim();
    }

    @Tool(description = "Reset all learned deliverable preferences. Use when the user says "
            + "'forget my preferences', 'start fresh on what I like', 'reset feedback'. The "
            + "next deliverable will be planned without preference bias (precedents still apply).")
    public String clearLearnedPreferences() {
        notifier.notify("🗑  clearing preferences…");
        int n = feedback.totalSignalsTracked();
        feedback.clear();
        return "Cleared preferences on " + n + " signal" + (n == 1 ? "" : "s") + ". "
                + "Next deliverable starts with no learned bias.";
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private static List<String> parseList(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String raw : csv.split(",")) {
            String s = raw.trim().toLowerCase(Locale.ROOT)
                    .replace(' ', '-').replace('_', '-');
            if (s.isEmpty()) continue;
            // Loose normalization for common synonyms — lets the LLM pass natural language.
            switch (s) {
                case "price", "pricing"             -> s = "prices";
                case "link", "url", "urls"          -> s = "links";
                case "image", "photo", "photos", "pictures", "pics" -> s = "images";
                case "comparison", "comparison-tables", "table", "tables" -> s = "comparison-table";
                case "rec", "recommendations", "verdict" -> s = "recommendation";
                case "rankings", "top", "leaderboard"-> s = "ranking";
                case "spec"                         -> s = "specs";
                case "pros", "cons", "pros-and-cons"-> s = "pros-cons";
                default -> { /* keep as-is — store accepts arbitrary signal names */ }
            }
            out.add(s);
        }
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String pad(String s, int w) {
        if (s == null) s = "";
        return s.length() >= w ? s : s + " ".repeat(w - s.length());
    }
}
