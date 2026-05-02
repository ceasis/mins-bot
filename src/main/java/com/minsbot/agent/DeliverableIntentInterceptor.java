package com.minsbot.agent;

import com.minsbot.agent.DeliverableExecutor.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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
    private static final Pattern INTENT = Pattern.compile(
            "(?i)\\b(?:create|generate|make|draft|produce|put together|write|compile|" +
            "i\\s+(?:want|need)|give\\s+me|prepare|build)\\s+(?:a\\s+|an\\s+|me\\s+a\\s+|the\\s+)?" +
            "(powerpoint|ppt|pptx|deck|slides?|pdf|word\\s+doc(?:ument)?|docx|memo)\\b" +
            "[\\s,:]*(?:report|brief|deck|document|presentation|file|copy)?\\s*(?:of|on|about|for|covering|comparing)?\\s*(.{4,400})$");

    /** Looser variant for messages that lead with the format word: "PDF on X". */
    private static final Pattern INTENT_LEADING_FORMAT = Pattern.compile(
            "(?i)^\\s*(powerpoint|ppt|pptx|deck|slides?|pdf|word\\s+doc(?:ument)?|docx|memo)\\b" +
            "[\\s,:]*(?:report|brief|deck|document|presentation|file)?\\s*(?:of|on|about|for|covering|comparing)?\\s*(.{4,400})$");

    /** Format-less variant: action verb + deliverable noun ("create a report on X",
     *  "compile a comparison of Y"). No file extension named — default to PDF. */
    private static final Pattern INTENT_NO_FORMAT = Pattern.compile(
            "(?i)\\b(?:create|generate|make|draft|produce|put together|write|compile|" +
            "i\\s+(?:want|need)|give\\s+me|prepare|build)\\s+(?:a\\s+|an\\s+|me\\s+a\\s+|the\\s+)?" +
            "(report|brief|deck|memo|document|presentation|comparison|write[- ]?up|whitepaper|analysis)\\b" +
            "\\s*(?:of|on|about|for|covering|comparing)?\\s*(.{4,400})$");

    @Autowired(required = false)
    private DeliverableExecutor executor;

    @Autowired(required = false)
    private com.minsbot.skillpack.SkillRegistry skillRegistry;

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

        Result r = executor.produce(m.goal, useFormat, useOutput);
        if (!r.ok()) return "Tried to produce a deliverable but failed: " + r.message();

        StringBuilder sb = new StringBuilder();
        sb.append("✅ Deliverable ready.\n\n");
        sb.append("File: ").append(r.path().toAbsolutePath()).append("\n");
        sb.append("Score: ").append(r.score()).append("/10  ·  Cycles: ").append(r.cycles());
        if (r.score() < 8) {
            sb.append("\n\nCritic's last note: ").append(r.message());
        }
        return sb.toString();
    }

    // ─── Internals ───────────────────────────────────────────────────────

    private record Match(String format, String output, String goal) {}

    private static Match match(String text) {
        Matcher m = INTENT.matcher(text);
        if (m.find()) return build(m.group(1), m.group(2));
        m = INTENT_LEADING_FORMAT.matcher(text);
        if (m.find()) return build(m.group(1), m.group(2));
        m = INTENT_NO_FORMAT.matcher(text);
        if (m.find()) return build("pdf", m.group(2));
        return null;
    }

    private static Match build(String formatWord, String goalRaw) {
        String w = formatWord.toLowerCase().replaceAll("\\s+", "");
        String format, output;
        switch (w) {
            case "powerpoint", "ppt", "pptx", "deck", "slide", "slides" -> {
                format = "slides"; output = "pptx";
            }
            case "pdf" -> {
                format = "report"; output = "pdf";
            }
            case "worddoc", "worddocument", "docx" -> {
                format = "report"; output = "docx";
            }
            case "memo" -> {
                format = "memo"; output = "docx";
            }
            default -> {
                format = "report"; output = "md";
            }
        }
        String goal = goalRaw == null ? "" : goalRaw.trim()
                .replaceAll("[\\s.!?,;:]+$", "")
                .replaceAll("^\\s*(?:please|pls|kindly|thanks)[\\s,]*", "");
        if (goal.isEmpty()) return null;
        return new Match(format, output, goal);
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
