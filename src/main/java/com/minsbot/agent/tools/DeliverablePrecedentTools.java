package com.minsbot.agent.tools;

import com.minsbot.agent.DeliverablePrecedentStore;
import com.minsbot.agent.DeliverablePrecedentStore.Precedent;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * AI-callable controls for the deliverable precedent store. Lets the user
 * inspect what the bot has learned, see what it would carry forward for a
 * given query, and reset the memory if it ever drifts wrong.
 *
 * <p>Mirror of {@link ResearchCacheTools} for the precedent-learning side
 * of the deliverable executor.
 */
@Component
public class DeliverablePrecedentTools {

    private final DeliverablePrecedentStore store;
    private final ToolExecutionNotifier notifier;

    public DeliverablePrecedentTools(DeliverablePrecedentStore store,
                                     ToolExecutionNotifier notifier) {
        this.store = store;
        this.notifier = notifier;
    }

    @Tool(description = "Show the most recent deliverables the bot has produced and what features "
            + "they included (prices, images, links, comparison-table, ranking, recommendation, "
            + "specs, pros-cons). The bot uses this history to anticipate what the user wants in "
            + "future similar reports. Use when the user asks 'what have you learned', 'show my "
            + "deliverable history', 'what reports do you remember'. Default limit 10, max 50.")
    public String listDeliverablePrecedents(
            @ToolParam(description = "How many recent deliverables to show (default 10, max 50)") Integer limit) {
        notifier.notify("📚 listing precedents…");
        int n = (limit == null || limit <= 0) ? 10 : Math.min(50, limit);
        List<Precedent> list = store.listRecent(n);
        if (list.isEmpty()) {
            return "No deliverable history yet. Once you ask me to produce a few reports, I'll "
                    + "start carrying their shape (prices/images/links/etc.) forward.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Deliverable history (").append(store.size()).append(" total, showing ")
          .append(list.size()).append(", newest first):\n\n");
        Instant now = Instant.now();
        for (Precedent p : list) {
            sb.append("• ").append(humanAge(p.timestamp(), now))
              .append("  ·  \"").append(truncate(p.goal(), 80)).append("\"")
              .append("  ·  ").append(p.format()).append("/").append(p.output());
            if (p.signals() != null && !p.signals().isEmpty()) {
                sb.append("\n    included: ").append(String.join(", ", p.signals()));
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    @Tool(description = "Show what past deliverables the bot would consider similar to a given "
            + "query — i.e. what shape it would carry forward into a new report on this topic. "
            + "Use when the user asks 'what would you remember about X', 'what patterns apply to "
            + "Y', or to debug why a report came out the way it did.")
    public String findRelevantPrecedents(
            @ToolParam(description = "Topic or goal text to look up similar past deliverables for") String query) {
        if (query == null || query.isBlank()) return "Provide a topic or goal text.";
        notifier.notify("🔎 matching precedents for: " + truncate(query, 50));
        String ctx = store.relevantPrecedentsAsContext(query);
        if (ctx.isEmpty()) {
            return "No similar past deliverables. The next report on this topic will be planned "
                    + "from scratch.";
        }
        return ctx.trim();
    }

    @Tool(description = "Permanently delete the bot's deliverable history (precedents). Use when "
            + "the user says 'forget what you've made', 'clear deliverable history', 'reset what "
            + "you remember about reports'. The next report will be planned from scratch with no "
            + "carried-forward preferences.")
    public String clearDeliverablePrecedents() {
        notifier.notify("🗑  clearing precedents…");
        int before = store.size();
        store.clear();
        return "Cleared " + before + " precedent" + (before == 1 ? "" : "s")
                + ". Future reports start with a blank slate.";
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private static String humanAge(String iso, Instant now) {
        try {
            Instant t = Instant.parse(iso);
            long s = Duration.between(t, now).toSeconds();
            if (s < 60) return "just now";
            if (s < 3600) return (s / 60) + "m ago";
            if (s < 86400) return (s / 3600) + "h ago";
            if (s < 86400 * 14) return (s / 86400) + "d ago";
            return (s / 86400 / 7) + "w ago";
        } catch (Exception e) {
            return "earlier";
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
