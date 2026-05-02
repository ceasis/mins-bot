package com.minsbot.agent.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the bot's @Tool registry.
 *
 * <p>This is the foundation eval harness — it doesn't call the LLM, but it
 * codifies invariants the LLM-facing surface needs to keep. Any future cluster
 * cleanup or refactor that re-introduces a demoted tool, ships an empty
 * description, or breaks a canonical tool will fail loudly here instead of
 * silently degrading routing quality.
 *
 * <p>Runs without booting the Spring context — just classpath reflection scan,
 * so it stays fast and headless-safe.
 */
class ToolRegistryLintTest {

    /** Methods that were intentionally demoted from LLM scope. Re-adding @Tool to any of
     *  these is a regression — they were duplicates / unsafe / lossy. */
    private static final Set<String> DEMOTED_METHODS = Set.of(
            // ScheduledTaskTools — in-memory, lost on restart. Replaced by RemindersTools.
            "ScheduledTaskTools#scheduleReminder",
            "ScheduledTaskTools#scheduleRecurring",
            "ScheduledTaskTools#scheduleRecurringAiTask",
            "ScheduledTaskTools#cancelScheduledTask",
            "ScheduledTaskTools#listScheduledTasks",
            "ScheduledTaskTools#getFiredReminders",
            // CronConfigTools — lossy NL→cron regex. Replaced by RemindersTools.
            "CronConfigTools#updateCronInfo",
            // RecurringTaskTools — full duplicate of RemindersTools surface.
            "RecurringTaskTools#scheduleDailyAiTask",
            "RecurringTaskTools#scheduleCronAiTask",
            "RecurringTaskTools#listRecurringTasks",
            "RecurringTaskTools#deleteRecurringTask",
            "RecurringTaskTools#setRecurringTaskEnabled",
            // BrowserTools.listBrowserTabs — less reliable than ChromeCdpTools.browserListOpenTabs.
            "BrowserTools#listBrowserTabs",
            // WebSearchTools.readWebPage — duplicate of WebScraperTools.fetchPageText.
            "WebSearchTools#readWebPage",
            // Video AI cluster — 7 text-to-video providers consolidated to Sora as canonical.
            "VeoVideoTools#generateVeoVideo",
            "VeoVideoTools#getVeoVideoStatus",
            "VeoVideoTools#downloadVeoVideo",
            "RunwayVideoTools#generateRunwayVideo",
            "RunwayVideoTools#getRunwayVideoStatus",
            "LumaVideoTools#generateLumaVideo",
            "LumaVideoTools#getLumaVideoStatus",
            "KlingVideoTools#generateKlingVideo",
            "KlingVideoTools#getKlingVideoStatus",
            "HailuoVideoTools#generateHailuoVideo",
            "HailuoVideoTools#getHailuoVideoStatus",
            "PikaVideoTools#generatePikaVideo",
            "PikaVideoTools#getPikaVideoStatus",
            "FalVideoTools#generateFalVideo",
            "FalVideoTools#getFalVideoStatus",
            // Talking-head providers consolidated to HeyGen as canonical.
            "DIDVideoTools#generateDIDTalk",
            "DIDVideoTools#getDIDTalkStatus",
            "SynthesiaVideoTools#generateSynthesiaVideo",
            "SynthesiaVideoTools#getSynthesiaVideoStatus",
            "TavusVideoTools#generateTavusVideo",
            "TavusVideoTools#getTavusVideoStatus",
            // Email cluster — Gmail API (OAuth) is canonical. SMTP/IMAP/browser-autoclick paths demoted.
            "EmailTools#sendEmail",
            "EmailTools#sendHtmlEmail",
            "EmailTools#readInbox"
    );

    /** Tools the LLM MUST be able to see for the bot's headline behaviors to work.
     *  If any of these loses its @Tool annotation, every "remind me" / "open url" /
     *  "search the web" command silently breaks. */
    private static final Set<String> CANONICAL_TOOLS = Set.of(
            "RemindersTools#createDailyReminder",
            "RemindersTools#createWeeklyReminder",
            "RemindersTools#createCronReminder",
            "RemindersTools#listReminders",
            "RemindersTools#deleteReminder",
            "BrowserTools#openUrl",
            "WebSearchTools#searchWeb",
            "WebScraperTools#fetchPageText",
            "SoraVideoTools#generateSoraVideo",
            "HeyGenTools#generateHeygenVideo",
            // The plan→execute→synthesize→critique→refine loop. Losing this
            // breaks the bot's ability to produce researched deliverables.
            "DeliverableTools#produceDeliverable",
            "DeliverablePrecedentTools#listDeliverablePrecedents",
            "DeliverablePrecedentTools#findRelevantPrecedents",
            "DeliverablePrecedentTools#clearDeliverablePrecedents",
            "DeliverableFeedbackTools#recordDeliverableFeedback",
            "DeliverableFeedbackTools#showLearnedPreferences",
            "DeliverableFeedbackTools#clearLearnedPreferences",
            "GmailApiTools#sendGmailViaApi",
            "GmailApiTools#getUnreadEmails",
            "GmailApiTools#getRecentEmails"
    );

    private static final int MIN_DESCRIPTION_CHARS = 30;

    /**
     * Current reality after the video-AI cluster cleanup: ~747 @Tool methods. Still
     * far above OpenAI's 128/request cap. Goal: drive this down to <250 via continued
     * cluster cleanup, then drop the classifier in ToolRouter entirely. The cap here
     * is a regression ratchet — "don't make it worse" — not the target. Lower it as
     * each cluster cleanup lands.
     *
     * Ratchet history:
     *   2026-05-01: 768 → 747 (video-AI cluster: 7 t2v + 3 talking-head providers demoted)
     *   2026-05-01: 747 → 744 (email cluster: SMTP/IMAP/browser-autoclick paths demoted, Gmail OAuth canonical)
     *   2026-05-01: 744 → 752 (added DeliverableTools, ResearchCacheTools, DeliverablePrecedentTools — preference learning surface)
     *   2026-05-01: 752 → 755 (added DeliverableFeedbackTools — explicit feedback loop)
     */
    private static final int MAX_TOTAL_TOOLS = 760;
    private static final int TARGET_TOTAL_TOOLS = 250;

    private List<Method> allToolMethods() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(org.springframework.stereotype.Service.class));

        List<Method> out = new ArrayList<>();
        for (var bd : scanner.findCandidateComponents("com.minsbot")) {
            try {
                Class<?> cls = Class.forName(bd.getBeanClassName());
                for (Method m : cls.getDeclaredMethods()) {
                    if (m.isAnnotationPresent(Tool.class)) {
                        out.add(m);
                    }
                }
            } catch (Throwable ignored) {
                // Skip classes that can't be loaded (e.g. missing optional deps in test env)
            }
        }
        return out;
    }

    private static String key(Method m) {
        return m.getDeclaringClass().getSimpleName() + "#" + m.getName();
    }

    // ─── Lints ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("No demoted method has been silently re-promoted to @Tool")
    void demotedMethodsStayDemoted() {
        List<String> regressions = new ArrayList<>();
        for (Method m : allToolMethods()) {
            String k = key(m);
            if (DEMOTED_METHODS.contains(k)) regressions.add(k);
        }
        assertThat(regressions)
                .as("These methods were intentionally removed from LLM scope. "
                        + "Re-adding @Tool reintroduces routing ambiguity / silent data loss. "
                        + "If you genuinely need to undemote one, also remove it from "
                        + "ToolRegistryLintTest.DEMOTED_METHODS with a comment explaining why.")
                .isEmpty();
    }

    @Test
    @DisplayName("All canonical tools are still exposed to the LLM")
    void canonicalToolsExist() {
        Set<String> present = new HashSet<>();
        for (Method m : allToolMethods()) present.add(key(m));
        Set<String> missing = new HashSet<>(CANONICAL_TOOLS);
        missing.removeAll(present);
        assertThat(missing)
                .as("Canonical tools missing from the LLM-facing registry. "
                        + "Without these the bot's headline commands stop working.")
                .isEmpty();
    }

    @Test
    @DisplayName("Every @Tool description is substantive (>= " + MIN_DESCRIPTION_CHARS + " chars)")
    void noShortDescriptions() {
        List<String> shorties = new ArrayList<>();
        for (Method m : allToolMethods()) {
            Tool t = m.getAnnotation(Tool.class);
            String desc = t.description() == null ? "" : t.description().trim();
            if (desc.length() < MIN_DESCRIPTION_CHARS) {
                shorties.add(key(m) + " (" + desc.length() + " chars: \"" + desc + "\")");
            }
        }
        assertThat(shorties)
                .as("@Tool descriptions under " + MIN_DESCRIPTION_CHARS + " chars give the model "
                        + "nothing to disambiguate against. Use the format: WHAT it does + WHEN to "
                        + "pick it (vs. similar tools) + EXAMPLE user phrases. See RemindersTools "
                        + "for the canonical template.")
                .isEmpty();
    }

    @Test
    @DisplayName("Total @Tool count is under the safety cap")
    void totalToolsUnderCap() {
        int total = allToolMethods().size();
        assertThat(total)
                .as("Total @Tool methods exceeded " + MAX_TOTAL_TOOLS + " (target " + TARGET_TOTAL_TOOLS + "). "
                        + "OpenAI's hard limit is 128 per request; Anthropic allows ~250. "
                        + "Even if you don't hit the API cap, more tools = more routing errors. "
                        + "Run a cluster-cleanup pass before adding new ones, then lower MAX_TOTAL_TOOLS to ratchet.")
                .isLessThanOrEqualTo(MAX_TOTAL_TOOLS);
    }

    @Test
    @DisplayName("No two tools have identical descriptions (silent duplicates)")
    void noDuplicateDescriptions() {
        Map<String, List<String>> byDesc = new HashMap<>();
        for (Method m : allToolMethods()) {
            String desc = m.getAnnotation(Tool.class).description();
            if (desc == null || desc.isBlank()) continue;
            byDesc.computeIfAbsent(desc.trim(), k -> new ArrayList<>()).add(key(m));
        }
        List<String> dups = new ArrayList<>();
        for (var e : byDesc.entrySet()) {
            if (e.getValue().size() > 1) {
                dups.add(e.getValue() + " all share description: \"" + truncate(e.getKey(), 80) + "\"");
            }
        }
        assertThat(dups)
                .as("Tools with identical descriptions force the model to guess between them. "
                        + "Either merge into one tool with a mode parameter, or differentiate the descriptions.")
                .isEmpty();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
