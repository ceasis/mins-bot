package com.minsbot.agent;

import com.minsbot.agent.DeliverablePrecedentStore.Precedent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Passive feedback detector. Runs on every user message and, if a deliverable
 * was produced recently, scans the message for reaction patterns
 * ("perfect", "you forgot prices", "I didn't need the images") and applies
 * them to {@link DeliverableFeedbackStore} without the LLM having to choose
 * to call any tool.
 *
 * <p>Why passive instead of LLM-classifier: zero added latency, zero token
 * cost per turn, and works even when the model forgets to pick the feedback
 * tool. Scope is bounded by the time gate — feedback only attributes when a
 * fresh deliverable exists, so casual conversation won't poison the profile.
 *
 * <p>Pure heuristic keyword matching. False positives capped by:
 * <ul>
 *   <li>Time window (default 30 minutes since last delivery)</li>
 *   <li>Conservative regex — bare "good" / "ok" don't fire; explicit
 *       reactions like "perfect" / "exactly right" do</li>
 *   <li>Confidence threshold downstream — single hits don't change the
 *       planner profile until they cross {@code MIN_CONFIDENCE_COUNT}</li>
 * </ul>
 */
@Service
public class DeliverableFeedbackInterceptor {

    private static final Logger log = LoggerFactory.getLogger(DeliverableFeedbackInterceptor.class);

    /** Time window after a delivery during which a user message is treated as
     *  potential feedback on it. Past this, we assume the topic has moved on. */
    private static final Duration FRESH_WINDOW = Duration.ofMinutes(30);

    /** Strong positive reactions. Conservative — doesn't include bare "ok" / "good"
     *  which are too ambiguous. */
    private static final Pattern POSITIVE = Pattern.compile(
            "\\b(perfect|exactly (?:right|what|the)|spot on|nailed it|love it|" +
            "great (?:work|job)|amazing|excellent|brilliant|that(?:'?s)? (?:perfect|great)|" +
            "exactly what i (?:wanted|asked))\\b",
            Pattern.CASE_INSENSITIVE);

    /** Strong negative reactions. */
    private static final Pattern NEGATIVE = Pattern.compile(
            "\\b(not what i (?:wanted|asked)|missed the mark|terrible|useless|bad job|" +
            "this is wrong|completely off|disappointing|that(?:'?s)? (?:wrong|bad|terrible))\\b",
            Pattern.CASE_INSENSITIVE);

    /** Captures: "you forgot the prices" / "where are the images" / "needs links" */
    private static final Pattern MISSING = Pattern.compile(
            "\\b(?:you (?:forgot|missed|didn'?t (?:include|add))|where (?:are|is) the|" +
            "needs?|missing|please add|should (?:also )?(?:have|include))\\s+" +
            "(?:the\\s+)?([a-z][\\w\\- ]{2,40})",
            Pattern.CASE_INSENSITIVE);

    /** Captures: "I didn't need the X" / "drop the Y" / "remove the Z" / "too much W" */
    private static final Pattern UNWANTED = Pattern.compile(
            "\\b(?:i (?:didn'?t (?:need|want)|don'?t (?:need|want))|drop the|remove the|" +
            "skip the|leave out|too (?:much|many)|cut the)\\s+" +
            "([a-z][\\w\\- ]{2,40})",
            Pattern.CASE_INSENSITIVE);

    /** Map raw captured words to canonical signal names. */
    private static final Map<String, String> SIGNAL_ALIASES = Map.ofEntries(
            Map.entry("price", "prices"),
            Map.entry("prices", "prices"),
            Map.entry("pricing", "prices"),
            Map.entry("cost", "prices"),
            Map.entry("costs", "prices"),
            Map.entry("link", "links"),
            Map.entry("links", "links"),
            Map.entry("url", "links"),
            Map.entry("urls", "links"),
            Map.entry("image", "images"),
            Map.entry("images", "images"),
            Map.entry("photo", "images"),
            Map.entry("photos", "images"),
            Map.entry("picture", "images"),
            Map.entry("pictures", "images"),
            Map.entry("pic", "images"),
            Map.entry("pics", "images"),
            Map.entry("comparison", "comparison-table"),
            Map.entry("comparison table", "comparison-table"),
            Map.entry("comparison-table", "comparison-table"),
            Map.entry("table", "comparison-table"),
            Map.entry("tables", "comparison-table"),
            Map.entry("recommendation", "recommendation"),
            Map.entry("recommendations", "recommendation"),
            Map.entry("verdict", "recommendation"),
            Map.entry("rec", "recommendation"),
            Map.entry("ranking", "ranking"),
            Map.entry("rankings", "ranking"),
            Map.entry("top picks", "ranking"),
            Map.entry("spec", "specs"),
            Map.entry("specs", "specs"),
            Map.entry("specifications", "specs"),
            Map.entry("pros", "pros-cons"),
            Map.entry("cons", "pros-cons"),
            Map.entry("pros and cons", "pros-cons"),
            Map.entry("pros-cons", "pros-cons")
    );

    @Autowired(required = false)
    private DeliverablePrecedentStore precedents;

    @Autowired(required = false)
    private DeliverableFeedbackStore feedback;

    /**
     * Scan a user message for feedback signals. Applies anything found to the
     * feedback store. Always returns silently — never throws, never blocks the
     * normal chat flow. Returns true if at least one signal was recorded
     * (callers may use this to acknowledge in the reply).
     */
    public boolean scan(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return false;
        if (feedback == null || precedents == null) return false;

        Precedent latest = precedents.latest();
        if (latest == null) return false;

        // Time gate — only attribute when a delivery is "fresh".
        try {
            Instant t = Instant.parse(latest.timestamp());
            if (Duration.between(t, Instant.now()).compareTo(FRESH_WINDOW) > 0) return false;
        } catch (Exception e) {
            return false;
        }

        boolean recorded = false;

        // Positive / negative thumbs.
        boolean pos = POSITIVE.matcher(userMessage).find();
        boolean neg = NEGATIVE.matcher(userMessage).find();
        if (pos && !neg) {
            feedback.recordLiked(latest.signals());
            log.info("[FeedbackScanner] +1 kept on {} signals from \"{}\"",
                    latest.signals() == null ? 0 : latest.signals().size(), trunc(userMessage));
            recorded = true;
        } else if (neg && !pos) {
            feedback.recordDisliked(latest.signals());
            log.info("[FeedbackScanner] +1 rejected on {} signals from \"{}\"",
                    latest.signals() == null ? 0 : latest.signals().size(), trunc(userMessage));
            recorded = true;
        }

        // Missing / unwanted signal extraction.
        Set<String> missing = extractCanonicalSignals(MISSING, userMessage);
        if (!missing.isEmpty()) {
            feedback.recordMissing(missing);
            log.info("[FeedbackScanner] missing+: {}", missing);
            recorded = true;
        }
        Set<String> unwanted = extractCanonicalSignals(UNWANTED, userMessage);
        if (!unwanted.isEmpty()) {
            feedback.recordUnwanted(unwanted);
            log.info("[FeedbackScanner] unwanted+: {}", unwanted);
            recorded = true;
        }

        return recorded;
    }

    // ─── Internals ───────────────────────────────────────────────────────

    private static Set<String> extractCanonicalSignals(Pattern p, String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = p.matcher(text);
        while (m.find()) {
            String raw = m.group(1).trim().toLowerCase(Locale.ROOT);
            // Strip trailing fluff so "the prices in the report" doesn't include "in the report".
            raw = raw.replaceAll("\\s+(in|on|of|from|for|to|at|the)\\b.*$", "").trim();
            if (raw.isEmpty()) continue;
            String canonical = SIGNAL_ALIASES.get(raw);
            if (canonical == null) {
                // Try the first word alone — common case: "you forgot prices for the laptops"
                String firstWord = raw.split("\\s+")[0];
                canonical = SIGNAL_ALIASES.get(firstWord);
            }
            if (canonical != null) out.add(canonical);
        }
        return out;
    }

    private static String trunc(String s) {
        if (s == null) return "";
        return s.length() <= 60 ? s : s.substring(0, 60) + "…";
    }
}
