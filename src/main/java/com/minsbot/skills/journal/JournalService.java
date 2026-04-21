package com.minsbot.skills.journal;

import com.minsbot.TranscriptService;
import com.minsbot.agent.AsyncMessageService;
import com.minsbot.agent.EpisodicMemoryService;
import com.minsbot.agent.tools.AppUsageTrackerTools;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * End-of-day journal. At 21:00 local time the bot compiles:
 *  - Top apps you used today (from AppUsageTrackerTools)
 *  - Today's episodic-memory highlights
 *  - Recent chat topics
 *  - Saves as a journal-type episode for future recall.
 */
@Service
public class JournalService {

    private static final Logger log = LoggerFactory.getLogger(JournalService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Value("${app.skills.journal.enabled:true}")
    private boolean enabled;

    @Value("${app.skills.journal.cron:0 0 21 * * *}")
    private String cron;

    @Autowired(required = false) private ChatClient chatClient;
    @Autowired(required = false) private AppUsageTrackerTools appUsage;
    @Autowired(required = false) private EpisodicMemoryService episodic;
    @Autowired(required = false) private AsyncMessageService asyncMessages;
    @Autowired(required = false) private TranscriptService transcript;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "journal-scheduler"); t.setDaemon(true); return t;
    });

    @PostConstruct
    public void init() {
        if (!enabled) { log.info("[Journal] Disabled"); return; }
        scheduleNext();
    }

    private void scheduleNext() {
        try {
            CronExpression expr = CronExpression.parse(cron);
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime next = expr.next(now);
            if (next == null) return;
            long delayMs = java.time.Duration.between(now, next).toMillis();
            scheduler.schedule(() -> {
                try { writeJournal(false); } catch (Exception e) { log.warn("[Journal] auto-fire failed: {}", e.getMessage()); }
                scheduleNext();
            }, Math.max(1000, delayMs), TimeUnit.MILLISECONDS);
            log.info("[Journal] Next entry at {}", next);
        } catch (Exception e) {
            log.warn("[Journal] Could not schedule: {}", e.getMessage());
        }
    }

    @Tool(description = "Skill: write today's end-of-day journal entry now. Pulls app usage, episodic highlights, "
            + "and recent chat topics, then asks the AI to compose a reflective 2-3 paragraph journal entry "
            + "and saves it to episodic memory with type='journal'. Use when the user says 'write my journal', "
            + "'reflect on today', 'end-of-day journal', 'recap my day'.")
    public String writeTodayJournalNow() {
        return writeJournal(true);
    }

    private String writeJournal(boolean userTriggered) {
        if (chatClient == null) return "AI not configured — cannot generate journal.";

        String today = LocalDate.now().format(DATE_FMT);
        StringBuilder ctx = new StringBuilder();
        ctx.append("Date: ").append(today).append("\n\n");

        if (appUsage != null) {
            try { ctx.append("— App usage today —\n").append(appUsage.getAppUsageSummary("")).append("\n\n"); }
            catch (Exception ignored) {}
        }
        if (episodic != null) {
            try {
                java.util.List<java.util.Map<String, Object>> eps = episodic.searchEpisodes(today, 10);
                if (eps != null && !eps.isEmpty()) {
                    ctx.append("— Memories from today —\n");
                    for (java.util.Map<String, Object> ep : eps) {
                        Object summary = ep.get("summary");
                        if (summary != null) ctx.append("• ").append(summary).append("\n");
                    }
                    ctx.append("\n");
                }
            } catch (Exception ignored) {}
        }
        if (transcript != null) {
            try {
                List<String> recent = transcript.getRecentMemory();
                if (recent != null && !recent.isEmpty()) {
                    int from = Math.max(0, recent.size() - 40);
                    StringBuilder chat = new StringBuilder();
                    for (int i = from; i < recent.size(); i++) chat.append(recent.get(i)).append('\n');
                    if (chat.length() > 0) ctx.append("— Recent chat —\n").append(chat).append('\n');
                }
            } catch (Exception ignored) {}
        }

        String prompt = "You are writing a personal end-of-day journal for the user. Based on the facts below, "
                + "write a reflective 2-3 paragraph journal entry in second-person ('you did X, you felt Y'). "
                + "Highlight what was accomplished, any patterns you notice, and one suggestion for tomorrow. "
                + "Be warm and concrete. Do NOT invent details that aren't in the facts.\n\nFacts:\n" + ctx;

        String entry;
        try {
            entry = chatClient.prompt()
                    .system("Write a concise, warm, factual journal entry. 2-3 paragraphs. No headers, no lists.")
                    .user(prompt)
                    .call().content();
        } catch (Exception e) {
            log.warn("[Journal] AI call failed: {}", e.getMessage());
            return "Failed to generate journal: " + e.getMessage();
        }

        // Save to episodic memory. Signature: saveEpisode(type, summary, details, tags, people, importance)
        if (episodic != null && entry != null && !entry.isBlank()) {
            try {
                String trimmed = entry.strip();
                String shortSummary = "Journal — " + today + ": "
                        + (trimmed.length() > 140 ? trimmed.substring(0, 140) + "…" : trimmed);
                episodic.saveEpisode(
                        "journal",                                // type
                        shortSummary,                             // summary
                        trimmed,                                  // full details
                        java.util.List.of("daily", "reflection"), // tags
                        java.util.List.of(),                      // people
                        6);                                       // importance
            } catch (Exception e) {
                log.warn("[Journal] Save failed: {}", e.getMessage());
            }
        }

        if (asyncMessages != null) {
            asyncMessages.push("📓 **End-of-day journal (" + today + ")**\n\n" + (entry != null ? entry.strip() : ""));
        }
        log.info("[Journal] Entry written ({} chars, user-triggered={})",
                entry == null ? 0 : entry.length(), userTriggered);
        return userTriggered
                ? "📓 Journal written and saved to episodic memory."
                : "📓 Auto journal written.";
    }
}
