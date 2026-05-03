package com.minsbot.agent;

import com.minsbot.agent.DeliverableExecutor.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pre-LLM intent router for deliverable requests. When a user types something
 * like "POWERPOINT report of X" or "PDF on Y" or "Word doc covering Z",
 * we detect the intent deterministically and call {@link DeliverableExecutor}
 * directly — bypassing the LLM's tool-selection step entirely.
 *
 * <p>Why: with 750+ tools in scope, even strong description guidance has been
 * unreliable at steering the model to {@code produceDeliverable}. The LLM
 * was picking {@code createPdfDocument} or opening the PowerPoint app and
 * taking screenshots. Description text is a hint; this interceptor is a fence.
 *
 * <p>Pattern matched: {@code <FORMAT-WORD> ... (report|brief|memo|deck|"")}
 * followed by a topic. Format word is mandatory — without it the message
 * routes to the LLM as normal chat.
 */
@Service
public class DeliverableIntentInterceptor {

    private static final Logger log = LoggerFactory.getLogger(DeliverableIntentInterceptor.class);

    /**
     * Triggers ONLY when the user's message contains a clear file-format
     * keyword. Bare "report on X" / "brief about Y" stays with the LLM —
     * we don't want to hijack every research request.
     *
     * Captures: 1=format-word, rest=goal text.
     */
    /** Action verbs that open a deliverable request — shared by every skill. */
    private static final String VERBS =
            "(?:create|generate|make|draft|produce|put\\s+together|write|compile|"
          + "i\\s+(?:want|need)|give\\s+me|prepare|build)";
    /** Optional article between verb and format word. */
    private static final String ARTICLE = "(?:a\\s+|an\\s+|me\\s+a\\s+|the\\s+)?";
    /** Optional noun after the format word ("pdf report", "word doc", "ppt deck"). */
    private static final String NOUN_TAIL =
            "[\\s,:]*(?:report|brief|deck|document|presentation|file|copy)?"
          + "\\s*(?:of|on|about|for|covering|comparing)?\\s*";
    /** Captured-goal tail. (?is) = case-insensitive + DOTALL so multi-line scope text matches. */
    private static final String GOAL_TAIL = "(.{4,400})$";

    /** Format-less fallback regex: "create a report on X", "compile a comparison of Y".
     *  No file extension named — defaults the matched skill lookup to format=report,
     *  output=pdf. Stays in Java because it's a generic-shape catcher; per-format
     *  keywords are owned by the skill packs (see {@link #buildKeywordPatterns}). */
    private static final Pattern INTENT_NO_FORMAT = Pattern.compile(
            "(?is)\\b" + VERBS + "\\s+" + ARTICLE
          + "(report|brief|deck|memo|document|presentation|comparison|write[- ]?up|whitepaper|analysis)\\b"
          + "\\s*(?:of|on|about|for|covering|comparing)?\\s*" + GOAL_TAIL);

    @Autowired(required = false)
    private DeliverableExecutor executor;

    @Autowired(required = false)
    private com.minsbot.skillpack.SkillRegistry skillRegistry;

    @Autowired(required = false)
    private com.minsbot.agent.tools.PlaywrightService playwrightService;

    /** ChatService invokes this interceptor from TWO call sites per user
     *  message (entry queue + agent main loop). Without dedup, both fire
     *  executor.produce() for the same prompt — the user sees identical
     *  "Deliverable ready" replies stacked, and the .docx/.pdf gets generated
     *  twice. We remember the last (hash, replyTimestamp) and short-circuit
     *  duplicates within a 60s window. */
    private volatile int lastHandledHash = 0;
    private volatile long lastHandledMs = 0;
    private volatile String lastHandledReply = null;
    private static final long DEDUP_WINDOW_MS = 60_000;

    /**
     * Try to interpret the user message as a deliverable request. Returns null
     * if no intent matched (caller proceeds with normal LLM routing) or a
     * pre-formatted bot reply when the deliverable was produced directly.
     */
    public String interceptIfDeliverable(String userMessage) {
        if (userMessage == null || userMessage.isBlank() || executor == null) return null;
        String text = userMessage.trim();
        // Don't fire on short replies / one-word answers — they're conversation, not requests.
        if (text.length() < 12) return null;

        // Dedup: a second interceptor invocation for the same prompt within
        // the dedup window returns the cached reply WITHOUT re-running the
        // executor. The caller still gets a non-null short-circuit so its
        // own logic (return-from-method, transcript save) proceeds normally.
        int hash = text.hashCode();
        long now = System.currentTimeMillis();
        if (hash == lastHandledHash && (now - lastHandledMs) < DEDUP_WINDOW_MS
                && lastHandledReply != null) {
            log.info("[DeliverableIntent] dedup: same prompt within {}ms — returning cached reply",
                    now - lastHandledMs);
            return lastHandledReply;
        }

        Match m = match(text);
        if (m == null) return null;

        // Prefer a declared skill pack over the regex-inferred defaults. If the
        // user has installed a skill whose `metadata.minsbot.output` matches the
        // inferred output (e.g. generate-pdf-report → pdf), use ITS declared
        // format/output so the skill is the source of truth — not this regex.
        com.minsbot.skillpack.SkillManifest skill = findMatchingSkill(m.output);
        String useFormat = m.format;
        String useOutput = m.output;
        String viaSkill = null;
        if (skill != null) {
            viaSkill = skill.name();
            if (skill.format() != null && !skill.format().isBlank()) useFormat = skill.format();
            if (skill.output() != null && !skill.output().isBlank()) useOutput = skill.output();
        }

        log.info("[DeliverableIntent] matched: format={} output={} skill={} goal=\"{}\"",
                useFormat, useOutput, viaSkill == null ? "(none)" : viaSkill, truncate(m.goal, 80));

        // Apply per-skill Playwright visibility override (if the skill declared
        // metadata.minsbot.playwright.show-browser). Cleared in finally so a
        // later prompt that doesn't match a skill reverts to the global default.
        boolean overrideApplied = false;
        if (skill != null && skill.showPlaywrightBrowser() != null && playwrightService != null) {
            try {
                playwrightService.setShowBrowserOverride(skill.showPlaywrightBrowser());
                overrideApplied = true;
                log.info("[DeliverableIntent] Playwright show-browser override = {} (from skill '{}')",
                        skill.showPlaywrightBrowser(), skill.name());
            } catch (Exception e) {
                log.warn("[DeliverableIntent] failed to apply Playwright override: {}", e.getMessage());
            }
        }

        Result r;
        try {
            r = executor.produce(m.goal, useFormat, useOutput);
        } finally {
            if (overrideApplied) {
                try { playwrightService.setShowBrowserOverride(null); } catch (Exception ignored) {}
            }
        }
        if (!r.ok()) return "Tried to produce a deliverable but failed: " + r.message();

        StringBuilder sb = new StringBuilder();
        sb.append("✅ Deliverable ready.\n\n");
        sb.append("File: ").append(r.path().toAbsolutePath()).append("\n");
        sb.append("Score: ").append(r.score()).append("/10  ·  Cycles: ").append(r.cycles());
        if (r.score() < 8) {
            // Don't dump the full multi-bullet critic note + budget line into chat
            // — that's a wall of text the user can't scan. One short headline,
            // and point at the audit folder where the full critique lives.
            sb.append("\n\nNote: scored below ship bar. Full critique in ");
            if (r.workDir() != null) {
                sb.append(r.workDir().toAbsolutePath()).append("\\critique-").append(r.cycles()).append(".md");
            } else {
                sb.append("the task folder");
            }
            sb.append(".");
        }
        String reply = sb.toString();
        // Cache for the duplicate-call path (see lastHandled fields above).
        lastHandledHash = hash;
        lastHandledMs = System.currentTimeMillis();
        lastHandledReply = reply;
        return reply;
    }

    // ─── Internals ───────────────────────────────────────────────────────

    private record Match(String format, String output, String goal) {}

    /** Try to match {@code text} against any installed skill's declared
     *  {@code metadata.minsbot.triggers.keywords}, then fall back to the
     *  generic format-less regex. The skill MD owns ALL format-keyword
     *  routing; this method just walks the registry. */
    private Match match(String text) {
        if (skillRegistry != null) {
            for (com.minsbot.skillpack.SkillManifest s : skillRegistry.forCurrentOs()) {
                if (s.output() == null || s.output().isBlank()) continue;
                List<String> kws = s.triggerKeywords();
                if (kws == null || kws.isEmpty()) continue;
                Pattern[] patterns = compileKeywordPatterns(kws);
                for (Pattern p : patterns) {
                    Matcher m = p.matcher(text);
                    if (m.find()) {
                        String goal = cleanGoal(m.group(1));
                        if (goal == null) return null;
                        String fmt = s.format() == null || s.format().isBlank() ? "report" : s.format();
                        return new Match(fmt, s.output(), goal);
                    }
                }
            }
        }
        // Fallback: generic deliverable noun without an explicit format word
        // ("create a report on X"). Defaults to PDF — generate-pdf-report's
        // skill match in interceptIfDeliverable will refine via its own data.
        Matcher m = INTENT_NO_FORMAT.matcher(text);
        if (m.find()) {
            String goal = cleanGoal(m.group(2));
            if (goal == null) return null;
            return new Match("report", "pdf", goal);
        }
        return null;
    }

    /** Trim surrounding punctuation and politeness fluff from the captured
     *  goal text. Returns null when the goal is empty after cleanup. */
    private static String cleanGoal(String raw) {
        if (raw == null) return null;
        String goal = raw.trim()
                .replaceAll("[\\s.!?,;:]+$", "")
                .replaceAll("^\\s*(?:please|pls|kindly|thanks)[\\s,]*", "");
        return goal.isEmpty() ? null : goal;
    }

    /** Build two anchor patterns per skill: the verb-led shape ("i need X
     *  report on Y") and the leading-format shape ("X on Y"). Both honor
     *  {@link #VERBS}, {@link #ARTICLE}, {@link #NOUN_TAIL}, {@link #GOAL_TAIL}
     *  so adding a skill is purely a keyword exercise. */
    private static Pattern[] compileKeywordPatterns(List<String> keywords) {
        // Sort longer keywords first so "word document" wins over "word".
        List<String> sorted = new java.util.ArrayList<>(keywords);
        sorted.sort((a, b) -> Integer.compare(b.length(), a.length()));
        StringBuilder alt = new StringBuilder();
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) alt.append('|');
            // Each keyword may contain spaces — replace with \\s+ so the regex
            // matches the user typing one or many spaces between tokens.
            alt.append(sorted.get(i).trim().replaceAll("\\s+", "\\\\s+"));
        }
        String kwGroup = "(?:" + alt + ")";
        Pattern verbLed = Pattern.compile(
                "(?is)\\b" + VERBS + "\\s+" + ARTICLE + kwGroup + "\\b" + NOUN_TAIL + GOAL_TAIL);
        Pattern leading = Pattern.compile(
                "(?is)^\\s*" + kwGroup + "\\b" + NOUN_TAIL + GOAL_TAIL);
        return new Pattern[] { verbLed, leading };
    }

    /** Find an installed skill whose {@code metadata.minsbot.output} matches
     *  the regex-inferred output ext (e.g. "pdf" → generate-pdf-report). Returns
     *  null when no skill matches or the registry isn't available. */
    private com.minsbot.skillpack.SkillManifest findMatchingSkill(String inferredOutput) {
        if (skillRegistry == null || inferredOutput == null) return null;
        String want = inferredOutput.trim().toLowerCase();
        for (com.minsbot.skillpack.SkillManifest s : skillRegistry.forCurrentOs()) {
            String out = s.output();
            if (out != null && out.trim().equalsIgnoreCase(want)) return s;
        }
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
