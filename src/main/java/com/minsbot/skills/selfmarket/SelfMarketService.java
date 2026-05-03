package com.minsbot.skills.selfmarket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minsbot.skills.adcopygen.AdCopyGenService;
import com.minsbot.skills.competitor.CompetitorService;
import com.minsbot.skills.contentresearch.ContentResearchService;
import com.minsbot.skills.emailsender.EmailSenderConfig;
import com.minsbot.skills.emailsender.EmailSenderService;
import com.minsbot.skills.landingpageaudit.LandingPageAuditService;
import com.minsbot.skills.proposalwriter.ProposalWriterService;
import com.minsbot.skills.reviewmonitor.ReviewMonitorService;
import com.minsbot.skills.socialposter.SocialPosterConfig;
import com.minsbot.skills.socialposter.SocialPosterService;
import com.minsbot.skills.socialschedule.SocialScheduleService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/**
 * "Market yourself" orchestrator — chains content/competitor/adcopy/social/audit
 * skills to produce a daily marketing playbook for Mins Bot (or any product).
 *
 * Honest scope: GENERATES the campaign; does NOT auto-post or buy ads.
 * You (or a connected integration with API keys) execute the playbook.
 */
@Service
public class SelfMarketService {

    private final SelfMarketConfig.SelfMarketProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private Path dir;

    @Autowired(required = false) private ContentResearchService contentResearch;
    @Autowired(required = false) private CompetitorService competitor;
    @Autowired(required = false) private AdCopyGenService adCopy;
    @Autowired(required = false) private SocialScheduleService social;
    @Autowired(required = false) private LandingPageAuditService lpAudit;
    @Autowired(required = false) private ProposalWriterService proposal;
    @Autowired(required = false) private ReviewMonitorService reviewMonitor;
    @Autowired(required = false) private SocialPosterService socialPoster;
    @Autowired(required = false) private SocialPosterConfig.SocialPosterProperties socialPosterProps;
    @Autowired(required = false) private EmailSenderService emailSender;
    @Autowired(required = false) private EmailSenderConfig.EmailSenderProperties emailSenderProps;

    public SelfMarketService(SelfMarketConfig.SelfMarketProperties props) { this.props = props; }

    @PostConstruct
    void init() throws IOException {
        dir = Paths.get(props.getStorageDir());
        Files.createDirectories(dir);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> override) throws IOException {
        Map<String, Object> o = override == null ? Map.of() : override;
        String product = str(o, "product", props.getProduct());
        String tagline = str(o, "tagline", props.getTagline());
        String landing = str(o, "landingPage", props.getLandingPage());
        String audience = str(o, "audience", props.getAudience());
        List<String> contentSources = listOr(o, "contentSources", props.getContentSources());
        List<String> competitorSites = listOr(o, "competitorSites", props.getCompetitorSites());
        List<String> reviewSources = listOr(o, "reviewSources", props.getReviewSources());
        List<String> topics = listOr(o, "topics", props.getTrendingTopics());
        List<String> platforms = listOr(o, "postPlatforms", props.getPostPlatforms());
        List<Map<String, Object>> influencers = (List<Map<String, Object>>) o.getOrDefault("influencers", List.of());

        Map<String, Object> playbook = new LinkedHashMap<>();
        playbook.put("skill", "selfmarket");
        playbook.put("date", LocalDate.now().toString());
        playbook.put("product", product);
        playbook.put("tagline", tagline);
        playbook.put("landingPage", landing);
        playbook.put("audience", audience);

        List<String> nextActions = new ArrayList<>();
        Map<String, Object> stages = new LinkedHashMap<>();

        // 1. Trend research
        if (contentResearch != null && !contentSources.isEmpty()) {
            try {
                Map<String, Object> r = contentResearch.run(topics, contentSources, 7, 15);
                stages.put("trends", r);
                List<?> trending = (List<?>) r.get("trendingTopics");
                if (trending != null && !trending.isEmpty()) nextActions.add(
                        "Write today's post around: " + trending.get(0));
            } catch (Exception e) { stages.put("trendsError", e.getMessage()); }
        }

        // 2. Competitor positioning
        if (competitor != null && !competitorSites.isEmpty()) {
            try {
                Map<String, Object> r = competitor.analyze(competitorSites);
                stages.put("competitors", r);
                List<?> commonCtas = (List<?>) r.get("commonCtas");
                if (commonCtas != null && !commonCtas.isEmpty()) nextActions.add(
                        "Differentiate from common competitor CTA: " + commonCtas.get(0));
            } catch (Exception e) { stages.put("competitorsError", e.getMessage()); }
        }

        // 3. Ad copy
        if (adCopy != null) {
            try {
                Map<String, Object> r = adCopy.generate(product, audience,
                        "save hours/week with one floating desktop bot",
                        topics, 12);
                stages.put("adCopy", r);
                nextActions.add("Pick top 3 headlines from adCopy.headlines and launch as Google/Meta variants");
            } catch (Exception e) { stages.put("adCopyError", e.getMessage()); }
        }

        // 4. Social posts
        if (social != null) {
            try {
                Map<String, Object> r = social.generate(
                        tagline + " — " + landing,
                        "Try " + product + " free → " + landing,
                        topics, platforms);
                stages.put("socialPosts", r);
                nextActions.add("Schedule today's posts from socialPosts.posts (each platform validated)");
            } catch (Exception e) { stages.put("socialPostsError", e.getMessage()); }
        }

        // 5. Landing page audit
        if (lpAudit != null && landing != null && !landing.isBlank()) {
            try {
                Map<String, Object> r = lpAudit.audit(landing);
                stages.put("landingPageAudit", r);
                Object score = r.get("score");
                List<?> issues = (List<?>) r.get("issues");
                if (issues != null && !issues.isEmpty()) nextActions.add(
                        "Fix top landing-page issue (score=" + score + "): " + issues.get(0));
            } catch (Exception e) { stages.put("landingPageAuditError", e.getMessage()); }
        }

        // 6. Influencer/journalist outreach drafts
        if (proposal != null && !influencers.isEmpty()) {
            List<Map<String, Object>> outreach = new ArrayList<>();
            for (Map<String, Object> inf : influencers) {
                String name = (String) inf.getOrDefault("name", "there");
                String snippet = (String) inf.getOrDefault("snippet", "your work on " + product);
                Map<String, Object> r = proposal.write(snippet,
                        product + " team", "introducing " + product + " — " + tagline,
                        "free to try, no card",
                        List.of(tagline, "100+ built-in skills"));
                outreach.add(Map.of("recipient", name, "variants", r.get("variants")));
            }
            stages.put("outreachDrafts", outreach);
            nextActions.add("Send " + outreach.size() + " outreach drafts (pick 1 variant each)");
        }

        // 7. Review monitoring
        if (reviewMonitor != null && !reviewSources.isEmpty()) {
            try {
                Map<String, Object> r = reviewMonitor.scan(reviewSources);
                stages.put("reviews", r);
                long neg = ((Number) r.getOrDefault("negative", 0)).longValue();
                if (neg > 0) nextActions.add("Reply to " + neg + " negative reviews using suggested templates");
            } catch (Exception e) { stages.put("reviewsError", e.getMessage()); }
        }

        // 8. Auto-execute (if requested) — actually publish posts + send emails
        boolean executeNow = Boolean.TRUE.equals(o.get("executeNow"));
        if (executeNow) {
            Map<String, Object> exec = new LinkedHashMap<>();
            exec.put("socialPosts", autoPublishSocial(stages));
            exec.put("outreachEmails", autoSendOutreach(influencers, product, tagline, landing));
            playbook.put("executionResults", exec);
            // Replace draft-style "todayActions" with execution-aware ones
            List<String> done = new ArrayList<>();
            Map<String, Object> sp = (Map<String, Object>) exec.get("socialPosts");
            if (sp != null) done.add("Social: " + sp.get("summary"));
            Map<String, Object> oe = (Map<String, Object>) exec.get("outreachEmails");
            if (oe != null) done.add("Email: " + oe.get("summary"));
            for (String a : nextActions) if (!a.toLowerCase().startsWith("schedule") && !a.toLowerCase().startsWith("send "))
                done.add(a);
            playbook.put("todayActions", done);
            playbook.put("disclaimer", "Auto-execute mode: posts + emails were actually sent for configured providers. " +
                    "1M users is a multi-year outcome — the bot's job is to run the daily loop reliably.");
        } else {
            playbook.put("todayActions", nextActions);
            playbook.put("disclaimer", "Preview mode: this generates the playbook only. Pass executeNow=true to publish/send. " +
                    "1M users is a multi-year outcome — the bot's job is to run the daily loop reliably.");
        }

        playbook.put("stages", stages);
        playbook.put("executeNow", executeNow);

        Path file = dir.resolve(LocalDate.now() + ".json");
        Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(playbook));
        playbook.put("storedAt", file.toAbsolutePath().toString());
        return playbook;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> autoPublishSocial(Map<String, Object> stages) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (socialPoster == null || socialPosterProps == null || !socialPosterProps.isEnabled()) {
            out.put("summary", "skipped — socialposter disabled or not configured");
            out.put("posted", 0);
            return out;
        }
        Map<String, Object> social = (Map<String, Object>) stages.get("socialPosts");
        if (social == null) { out.put("summary", "no social posts to publish"); out.put("posted", 0); return out; }
        List<Map<String, Object>> posts = (List<Map<String, Object>>) social.get("posts");
        if (posts == null || posts.isEmpty()) {
            out.put("summary", "no posts generated"); out.put("posted", 0); return out;
        }

        Set<String> supported = Set.of("bluesky", "mastodon", "webhook");
        List<Map<String, Object>> results = new ArrayList<>();
        int sent = 0, skipped = 0, failed = 0;
        for (Map<String, Object> post : posts) {
            String platform = String.valueOf(post.get("platform"));
            String text = String.valueOf(post.get("text"));
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("platform", platform);
            if (!supported.contains(platform)) {
                r.put("status", "skipped");
                r.put("reason", "platform " + platform + " not supported by socialposter (needs OAuth2)");
                skipped++;
            } else {
                try {
                    Map<String, Object> postResult = socialPoster.post(text, List.of(platform));
                    Map<String, Object> platResult = (Map<String, Object>) ((Map<String, Object>) postResult.get("results")).get(platform);
                    boolean ok = Boolean.TRUE.equals(platResult.get("ok"));
                    r.put("status", ok ? "posted" : "failed");
                    if (ok) { sent++; r.put("url", platResult.get("url")); r.put("uri", platResult.get("uri")); }
                    else { failed++; r.put("error", platResult.get("error")); }
                } catch (Exception e) {
                    r.put("status", "failed");
                    r.put("error", e.getMessage());
                    failed++;
                }
            }
            results.add(r);
        }
        out.put("summary", sent + " posted · " + skipped + " skipped · " + failed + " failed");
        out.put("posted", sent);
        out.put("skipped", skipped);
        out.put("failed", failed);
        out.put("details", results);
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> autoSendOutreach(List<Map<String, Object>> influencers, String product,
                                                 String tagline, String landing) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (emailSender == null || emailSenderProps == null || !emailSenderProps.isEnabled()) {
            out.put("summary", "skipped — emailsender disabled or not configured");
            out.put("sent", 0);
            return out;
        }
        if (influencers == null || influencers.isEmpty()) {
            out.put("summary", "no influencer list provided");
            out.put("sent", 0);
            return out;
        }

        List<Map<String, Object>> results = new ArrayList<>();
        int sent = 0, skipped = 0, failed = 0;
        for (Map<String, Object> inf : influencers) {
            String email = (String) inf.get("email");
            String name = (String) inf.getOrDefault("name", "there");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("recipient", name);
            if (email == null || email.isBlank() || !email.contains("@")) {
                r.put("status", "skipped");
                r.put("reason", "no email address");
                skipped++;
                results.add(r);
                continue;
            }
            try {
                String snippet = (String) inf.getOrDefault("snippet", "your work");
                Map<String, Object> draft = proposal.write(snippet, product + " team",
                        "introducing " + product + " — " + tagline,
                        "free to try, no card", List.of(tagline, "100+ built-in skills"));
                List<Map<String, Object>> variants = (List<Map<String, Object>>) draft.get("variants");
                Map<String, Object> chosen = variants.get(0); // pick "Direct" variant
                String subject = product + " — " + truncate(tagline, 50);
                String body = (String) chosen.get("fullText");
                Map<String, Object> sendResult = emailSender.send(email, subject, body, null,
                        "selfmarket-" + LocalDate.now());
                r.put("status", "sent");
                r.put("email", email);
                r.put("provider", sendResult.get("provider"));
                sent++;
            } catch (Exception e) {
                r.put("status", "failed");
                r.put("email", email);
                r.put("error", e.getMessage());
                failed++;
            }
            results.add(r);
        }
        out.put("summary", sent + " sent · " + skipped + " skipped · " + failed + " failed");
        out.put("sent", sent);
        out.put("skipped", skipped);
        out.put("failed", failed);
        out.put("details", results);
        return out;
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n - 3) + "...";
    }

    private static String str(Map<String, Object> m, String k, String fallback) {
        Object v = m.get(k);
        return v == null ? fallback : v.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> listOr(Map<String, Object> m, String k, List<String> fallback) {
        Object v = m.get(k);
        if (v instanceof List<?> l && !l.isEmpty()) return (List<String>) l;
        return fallback;
    }
}
