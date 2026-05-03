package com.minsbot.skills.emailcampaign;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Audits email subject lines + bodies for spam triggers, length, personalization
 * tokens, CTA presence, and link/image ratios. Returns a deliverability score
 * and a list of suggested fixes.
 */
@Service
public class EmailCampaignService {

    private final EmailCampaignConfig.EmailCampaignProperties props;

    private static final Set<String> SPAM_WORDS = Set.of(
            "free", "winner", "guarantee", "guaranteed", "risk-free", "urgent", "act now",
            "click here", "buy now", "limited time", "100%", "amazing", "cash", "prize",
            "earn money", "make money", "no cost", "no obligation", "no purchase", "satisfaction",
            "weight loss", "viagra", "miracle", "double your", "extra income", "income",
            "investment", "lottery", "lowest price", "promise you", "save big", "discount");

    private static final Pattern PERSONALIZATION = Pattern.compile(
            "\\{\\{\\s*[a-zA-Z_][a-zA-Z0-9_.]*\\s*\\}\\}|\\{[a-zA-Z_][a-zA-Z0-9_.]*\\}|%[A-Z_]+%");
    private static final Pattern URL = Pattern.compile("https?://[^\\s)>\"]+");
    private static final Pattern IMG = Pattern.compile("<img\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CTA_HINT = Pattern.compile(
            "\\b(click|tap|learn more|get started|sign up|book|reserve|claim|download|try|start|join|register|read more|see more|shop|buy|order|subscribe)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ALL_CAPS_RUN = Pattern.compile("\\b[A-Z]{4,}\\b");

    public EmailCampaignService(EmailCampaignConfig.EmailCampaignProperties props) {
        this.props = props;
    }

    public Map<String, Object> audit(String subject, String preheader, String body) {
        if (subject == null) subject = "";
        if (preheader == null) preheader = "";
        if (body == null) body = "";
        if (body.length() > props.getMaxBodyChars()) body = body.substring(0, props.getMaxBodyChars());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skill", "emailcampaign");

        Map<String, Object> subjReport = new LinkedHashMap<>();
        subjReport.put("text", subject);
        subjReport.put("length", subject.length());
        subjReport.put("inboxIdealUnder", 50);
        subjReport.put("mobileIdealUnder", 35);
        List<String> subjSpam = findSpamWords(subject);
        subjReport.put("spamWords", subjSpam);
        subjReport.put("hasEmoji", subject.codePoints().anyMatch(c -> c > 0x2700));
        subjReport.put("allCapsRuns", countMatches(ALL_CAPS_RUN, subject));
        subjReport.put("exclamations", countChar(subject, '!'));
        subjReport.put("personalizationTokens", countMatches(PERSONALIZATION, subject));

        Map<String, Object> bodyReport = new LinkedHashMap<>();
        bodyReport.put("length", body.length());
        bodyReport.put("urls", countMatches(URL, body));
        bodyReport.put("images", countMatches(IMG, body));
        bodyReport.put("personalizationTokens", countMatches(PERSONALIZATION, body));
        bodyReport.put("ctaHits", countMatches(CTA_HINT, body));
        bodyReport.put("spamWords", findSpamWords(body));
        bodyReport.put("allCapsRuns", countMatches(ALL_CAPS_RUN, body));
        bodyReport.put("exclamations", countChar(body, '!'));

        int score = 100;
        List<String> issues = new ArrayList<>();
        if (subject.isBlank()) { score -= 20; issues.add("Subject is empty"); }
        if (subject.length() > 70) { score -= 5; issues.add("Subject longer than 70 chars (truncation risk)"); }
        if (subjSpam.size() > 1) { score -= 10; issues.add("Multiple spam words in subject: " + subjSpam); }
        if ((int) subjReport.get("exclamations") > 1) { score -= 5; issues.add("Multiple ! in subject"); }
        if ((int) subjReport.get("allCapsRuns") > 0) { score -= 10; issues.add("ALL CAPS runs in subject"); }
        if ((int) subjReport.get("personalizationTokens") == 0) { score -= 5; issues.add("No personalization in subject"); }
        if (body.isBlank()) { score -= 30; issues.add("Body is empty"); }
        if ((int) bodyReport.get("ctaHits") == 0) { score -= 15; issues.add("No CTA detected in body"); }
        if (((List<?>) bodyReport.get("spamWords")).size() > 5) { score -= 10; issues.add("Many spam words in body"); }
        int urls = (int) bodyReport.get("urls");
        int images = (int) bodyReport.get("images");
        if (images > 0 && urls == 0) { score -= 5; issues.add("Images without text links (image-only emails get flagged)"); }
        if (preheader.isBlank()) { score -= 5; issues.add("Missing preheader (wastes inbox preview real estate)"); }

        result.put("subject", subjReport);
        result.put("preheader", Map.of("text", preheader, "length", preheader.length(),
                "idealRange", "40-100"));
        result.put("body", bodyReport);
        result.put("deliverabilityScore", Math.max(0, score));
        result.put("issues", issues);
        return result;
    }

    private static List<String> findSpamWords(String text) {
        String t = " " + text.toLowerCase(Locale.ROOT) + " ";
        List<String> hits = new ArrayList<>();
        for (String w : SPAM_WORDS) {
            if (t.contains(" " + w + " ") || t.contains(" " + w + ".") || t.contains(" " + w + "!")) {
                hits.add(w);
            }
        }
        return hits;
    }

    private static int countMatches(Pattern p, String s) {
        Matcher m = p.matcher(s);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }
}
