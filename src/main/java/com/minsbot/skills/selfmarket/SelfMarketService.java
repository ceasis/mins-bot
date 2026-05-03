package com.minsbot.skills.selfmarket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minsbot.skills.adcopygen.AdCopyGenService;
import com.minsbot.skills.competitor.CompetitorService;
import com.minsbot.skills.contentresearch.ContentResearchService;
import com.minsbot.skills.landingpageaudit.LandingPageAuditService;
import com.minsbot.skills.proposalwriter.ProposalWriterService;
import com.minsbot.skills.reviewmonitor.ReviewMonitorService;
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

        playbook.put("stages", stages);
        playbook.put("todayActions", nextActions);
        playbook.put("disclaimer", "This generates the playbook. Posting/sending requires your accounts/API keys. " +
                "1M users is a multi-year outcome — the bot's job is to run the daily loop reliably.");

        Path file = dir.resolve(LocalDate.now() + ".json");
        Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(playbook));
        playbook.put("storedAt", file.toAbsolutePath().toString());
        return playbook;
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
