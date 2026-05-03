package com.minsbot.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minsbot.skills.blogwriter.BlogWriterConfig;
import com.minsbot.skills.blogwriter.BlogWriterService;
import com.minsbot.skills.emailsender.EmailSenderConfig;
import com.minsbot.skills.emailsender.EmailSenderService;
import com.minsbot.skills.mentiontracker.MentionTrackerConfig;
import com.minsbot.skills.mentiontracker.MentionTrackerService;
import com.minsbot.skills.selfmarket.SelfMarketConfig;
import com.minsbot.skills.selfmarket.SelfMarketService;
import com.minsbot.skills.socialposter.SocialPosterConfig;
import com.minsbot.skills.socialposter.SocialPosterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Bridges the {@link SelfMarketService} skill to the agent so the LLM can
 * trigger a full marketing playbook from natural language: "market the app",
 * "market my product X", "do marketing for Acme", etc.
 *
 * The bot's persona is JARVIS, but the THING being marketed is the configured
 * product (default: Mins Bot / mins.io). Pass productName/tagline/landingPage
 * to market any other product.
 */
@Component
public class MarketingCampaignTools {

    private static final Logger log = LoggerFactory.getLogger(MarketingCampaignTools.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired(required = false) private SelfMarketService selfMarket;
    @Autowired(required = false) private SelfMarketConfig.SelfMarketProperties selfMarketProps;
    @Autowired(required = false) private SocialPosterService socialPoster;
    @Autowired(required = false) private SocialPosterConfig.SocialPosterProperties socialPosterProps;
    @Autowired(required = false) private EmailSenderService emailSender;
    @Autowired(required = false) private EmailSenderConfig.EmailSenderProperties emailSenderProps;
    @Autowired(required = false) private MentionTrackerService mentionTracker;
    @Autowired(required = false) private MentionTrackerConfig.MentionTrackerProperties mentionTrackerProps;
    @Autowired(required = false) private BlogWriterService blogWriter;
    @Autowired(required = false) private BlogWriterConfig.BlogWriterProperties blogWriterProps;
    @Autowired(required = false) private ToolExecutionNotifier notifier;

    @Tool(description = "Run a complete marketing campaign for the configured app (default: Mins Bot "
            + "at mins.io) AND ACTUALLY EXECUTE IT — publishes social posts to configured providers "
            + "(Bluesky/Mastodon/webhook) and sends outreach emails via Resend/SMTP. Use when the user "
            + "says 'market the app', 'market yourself', 'market mins bot', 'do marketing', 'launch a "
            + "campaign'. If providers aren't configured, those steps skip with a clear message — "
            + "nothing is generated and lost. Returns: what got published, what got sent, and what "
            + "still needs the user (e.g. landing-page fixes).")
    public String marketTheApp() {
        return runCampaign(null, null, null, null, true);
    }

    @Tool(description = "PREVIEW the marketing campaign WITHOUT publishing or sending anything. "
            + "Use when the user says 'preview the marketing', 'show me what you'd post', 'dry-run "
            + "the campaign', 'draft only'. Returns the same playbook as 'market the app' but nothing "
            + "actually goes live — useful for review before committing.")
    public String previewMarketing() {
        return runCampaign(null, null, null, null, false);
    }

    @Tool(description = "Run a complete marketing campaign for any product (not just the bot) AND "
            + "EXECUTE IT (publish + send). Use when the user says 'market <product>', 'launch "
            + "marketing for <product>', 'do marketing for <product> with landing page <url>'. "
            + "Auto-publishes to Bluesky/Mastodon/webhook and sends emails if those providers are "
            + "configured; otherwise skips those steps cleanly.")
    public String marketProduct(
            @ToolParam(description = "Product name to market, e.g. 'Acme CRM'") String productName,
            @ToolParam(description = "One-sentence tagline / value prop for the product. Optional.", required = false)
            String tagline,
            @ToolParam(description = "Landing page URL for the product, e.g. 'https://acme.com'. Optional.", required = false)
            String landingPage,
            @ToolParam(description = "Target audience description, e.g. 'small SaaS founders'. Optional.", required = false)
            String audience) {
        return runCampaign(productName, tagline, landingPage, audience, true);
    }

    private String runCampaign(String product, String tagline, String landingPage, String audience, boolean executeNow) {
        if (selfMarket == null || selfMarketProps == null) {
            return "Marketing skill is not loaded. Check that selfmarket is registered.";
        }
        if (!selfMarketProps.isEnabled()) {
            return "Marketing skill is disabled. Enable it: set app.skills.selfmarket.enabled=true "
                    + "in application.properties (and enable the dependent skills: contentresearch, "
                    + "competitor, adcopygen, socialschedule, landingpageaudit, proposalwriter, reviewmonitor).";
        }
        if (notifier != null) notifier.notify("📣 building marketing playbook...");

        Map<String, Object> override = new LinkedHashMap<>();
        if (product != null && !product.isBlank()) override.put("product", product);
        if (tagline != null && !tagline.isBlank()) override.put("tagline", tagline);
        if (landingPage != null && !landingPage.isBlank()) override.put("landingPage", landingPage);
        if (audience != null && !audience.isBlank()) override.put("audience", audience);
        override.put("executeNow", executeNow);

        try {
            Map<String, Object> playbook = selfMarket.run(override);
            return formatForChat(playbook);
        } catch (Exception e) {
            log.warn("selfmarket failed", e);
            return "Couldn't run marketing campaign: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private String formatForChat(Map<String, Object> playbook) {
        StringBuilder sb = new StringBuilder();
        boolean executed = Boolean.TRUE.equals(playbook.get("executeNow"));
        sb.append(executed ? "── marketing campaign · LIVE · " : "── marketing preview · ");
        sb.append(playbook.get("date")).append(" ──\n");
        sb.append("Product: ").append(playbook.get("product")).append("\n");
        Object landing = playbook.get("landingPage");
        if (landing != null) sb.append("Landing: ").append(landing).append("\n");
        sb.append("\n");

        // Execution results — what actually went live
        Map<String, Object> exec = (Map<String, Object>) playbook.get("executionResults");
        if (exec != null) {
            Map<String, Object> sp = (Map<String, Object>) exec.get("socialPosts");
            if (sp != null) {
                sb.append("📣 Social: ").append(sp.get("summary")).append("\n");
                List<Map<String, Object>> details = (List<Map<String, Object>>) sp.get("details");
                if (details != null) {
                    for (Map<String, Object> d : details) {
                        String status = String.valueOf(d.get("status"));
                        String icon = "posted".equals(status) ? "  ✓" : "skipped".equals(status) ? "  ·" : "  ✗";
                        sb.append(icon).append(" ").append(d.get("platform"));
                        if (d.get("url") != null) sb.append(" → ").append(d.get("url"));
                        else if (d.get("uri") != null) sb.append(" (").append(d.get("uri")).append(")");
                        else if (d.get("reason") != null) sb.append(" — ").append(d.get("reason"));
                        else if (d.get("error") != null) sb.append(" — ").append(d.get("error"));
                        sb.append("\n");
                    }
                }
            }
            Map<String, Object> oe = (Map<String, Object>) exec.get("outreachEmails");
            if (oe != null) {
                sb.append("✉ Email: ").append(oe.get("summary")).append("\n");
                List<Map<String, Object>> details = (List<Map<String, Object>>) oe.get("details");
                if (details != null) {
                    for (Map<String, Object> d : details) {
                        String status = String.valueOf(d.get("status"));
                        String icon = "sent".equals(status) ? "  ✓" : "skipped".equals(status) ? "  ·" : "  ✗";
                        sb.append(icon).append(" ").append(d.get("recipient"));
                        if (d.get("email") != null) sb.append(" <").append(d.get("email")).append(">");
                        if (d.get("reason") != null) sb.append(" — ").append(d.get("reason"));
                        if (d.get("error") != null) sb.append(" — ").append(d.get("error"));
                        sb.append("\n");
                    }
                }
            }
            sb.append("\n");
        }

        List<String> actions = (List<String>) playbook.get("todayActions");
        if (actions != null && !actions.isEmpty()) {
            sb.append(executed ? "📋 Still needs you:\n" : "📋 Today's actions:\n");
            for (int i = 0; i < actions.size(); i++) {
                sb.append("  ").append(i + 1).append(". ").append(actions.get(i)).append("\n");
            }
            sb.append("\n");
        }

        Map<String, Object> stages = (Map<String, Object>) playbook.get("stages");
        if (stages != null) {
            // Social posts preview
            Map<String, Object> social = (Map<String, Object>) stages.get("socialPosts");
            if (social != null) {
                List<Map<String, Object>> posts = (List<Map<String, Object>>) social.get("posts");
                if (posts != null && !posts.isEmpty()) {
                    sb.append("📱 Social posts (").append(posts.size()).append(" platforms):\n");
                    for (Map<String, Object> p : posts) {
                        sb.append("  • ").append(p.get("platform"))
                                .append(" (").append(p.get("length")).append("/").append(p.get("limit")).append("): ")
                                .append(truncate(String.valueOf(p.get("text")), 100)).append("\n");
                    }
                    sb.append("\n");
                }
            }

            // Ad copy preview
            Map<String, Object> ad = (Map<String, Object>) stages.get("adCopy");
            if (ad != null) {
                List<Map<String, Object>> heads = (List<Map<String, Object>>) ad.get("headlines");
                if (heads != null && !heads.isEmpty()) {
                    sb.append("🎯 Top ad headlines:\n");
                    int n = Math.min(5, heads.size());
                    for (int i = 0; i < n; i++) {
                        sb.append("  • ").append(heads.get(i).get("text")).append("\n");
                    }
                    sb.append("\n");
                }
            }

            // Landing page audit
            Map<String, Object> lp = (Map<String, Object>) stages.get("landingPageAudit");
            if (lp != null) {
                sb.append("🔍 Landing page score: ").append(lp.get("score")).append("/100\n");
                List<String> issues = (List<String>) lp.get("issues");
                if (issues != null && !issues.isEmpty()) {
                    int n = Math.min(3, issues.size());
                    for (int i = 0; i < n; i++) sb.append("  • ").append(issues.get(i)).append("\n");
                }
                sb.append("\n");
            }

            // Trends
            Map<String, Object> trends = (Map<String, Object>) stages.get("trends");
            if (trends != null) {
                List<?> trending = (List<?>) trends.get("trendingTopics");
                if (trending != null && !trending.isEmpty()) {
                    sb.append("📈 Trending: ");
                    for (int i = 0; i < Math.min(3, trending.size()); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(trending.get(i));
                    }
                    sb.append("\n\n");
                }
            }
        }

        Object stored = playbook.get("storedAt");
        if (stored != null) sb.append("💾 Full playbook saved: ").append(stored).append("\n");
        return sb.toString();
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        s = s.replace("\n", " ").trim();
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    // ──────────────────────────── Execution layer ────────────────────────────

    @Tool(description = "Publish a post to social media (Bluesky / Mastodon / generic webhook). "
            + "Use when the user says 'publish to bluesky', 'post this to mastodon', 'send to my "
            + "webhook', 'publish today's post', 'post to social'. Pass the text to publish and "
            + "optionally a list of platforms (bluesky, mastodon, webhook). If platforms is empty, "
            + "auto-detects all configured platforms. X (Twitter) and LinkedIn are NOT supported "
            + "directly — they need OAuth2; route those via webhook fan-out (Zapier/Make/n8n).")
    public String publishToSocial(
            @ToolParam(description = "The text to publish (will be truncated per platform: 300 Bluesky, 500 Mastodon)")
            String text,
            @ToolParam(description = "Comma-separated platforms: bluesky,mastodon,webhook. Empty = auto-detect configured.", required = false)
            String platforms) {
        if (socialPoster == null || socialPosterProps == null)
            return "socialposter skill is not loaded.";
        if (!socialPosterProps.isEnabled())
            return "socialposter skill is disabled. Set app.skills.socialposter.enabled=true and configure at least one provider (bluesky-handle+password, mastodon-instance+token, or webhook-url).";
        if (notifier != null) notifier.notify("📣 publishing to social...");
        try {
            List<String> plats = (platforms == null || platforms.isBlank())
                    ? List.of()
                    : Arrays.stream(platforms.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            Map<String, Object> r = socialPoster.post(text, plats);
            return formatSocialResult(r);
        } catch (Exception e) {
            return "Failed to publish: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private String formatSocialResult(Map<String, Object> r) {
        StringBuilder sb = new StringBuilder("📣 Social post results:\n");
        Map<String, Object> results = (Map<String, Object>) r.get("results");
        if (results == null || results.isEmpty()) return "No platforms configured. Set bluesky/mastodon/webhook in application.properties.";
        for (var e : results.entrySet()) {
            Map<String, Object> v = (Map<String, Object>) e.getValue();
            boolean ok = Boolean.TRUE.equals(v.get("ok"));
            sb.append("  ").append(ok ? "✓" : "✗").append(" ").append(e.getKey());
            if (ok) {
                Object url = v.get("url");
                Object uri = v.get("uri");
                if (url != null) sb.append(" → ").append(url);
                else if (uri != null) sb.append(" (uri: ").append(uri).append(")");
            } else {
                sb.append(" — ").append(v.get("error"));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    @Tool(description = "Send an email via Resend or SMTP. Auto-logs to outreachtracker. "
            + "Use when the user says 'send email to <person>', 'email this draft to <addr>', "
            + "'send the outreach to <addr>'. Daily cap protects against runaway loops. "
            + "For cold outreach at scale you still need a warmed-up domain with SPF/DKIM/DMARC — "
            + "this skill won't fix deliverability, only sends.")
    public String sendEmail(
            @ToolParam(description = "Recipient email address") String to,
            @ToolParam(description = "Email subject line") String subject,
            @ToolParam(description = "Plain-text body. Pass either body or html.", required = false) String body,
            @ToolParam(description = "HTML body (overrides plain text). Optional.", required = false) String html,
            @ToolParam(description = "Campaign tag for tracking, e.g. 'launch-week'. Optional.", required = false) String campaign) {
        if (emailSender == null || emailSenderProps == null)
            return "emailsender skill is not loaded.";
        if (!emailSenderProps.isEnabled())
            return "emailsender skill is disabled. Set app.skills.emailsender.enabled=true and configure resend-api-key OR smtp-host.";
        if (notifier != null) notifier.notify("✉ sending email to " + to + "...");
        try {
            Map<String, Object> r = emailSender.send(to, subject, body, html, campaign);
            StringBuilder sb = new StringBuilder("✉ email sent\n");
            sb.append("  to: ").append(r.get("to")).append("\n");
            sb.append("  via: ").append(r.get("provider")).append("\n");
            if (r.get("id") != null) sb.append("  id: ").append(r.get("id")).append("\n");
            sb.append("  daily: ").append(r.get("sentNumberToday")).append("/").append(r.get("dailyCap"));
            if (Boolean.TRUE.equals(r.get("loggedToOutreachTracker"))) sb.append(" (logged to outreachtracker)");
            return sb.toString();
        } catch (Exception e) {
            return "Failed to send email: " + e.getMessage();
        }
    }

    @Tool(description = "Check for new mentions of the product across configured search feeds. "
            + "Use when the user says 'any new mentions?', 'who's talking about us', 'check brand "
            + "mentions', 'check our brand'. Polls Google Alerts / Reddit search / HN Algolia / "
            + "Nitter X-search RSS feeds the user supplied. Deduplicates. Classifies sentiment.")
    public String checkMentions(
            @ToolParam(description = "Brand keywords to filter by, comma-separated. e.g. 'mins bot,mins.io'", required = false)
            String brandKeywords,
            @ToolParam(description = "RSS feed URLs to poll, comma-separated. Required.", required = false)
            String sources) {
        if (mentionTracker == null || mentionTrackerProps == null)
            return "mentiontracker skill is not loaded.";
        if (!mentionTrackerProps.isEnabled())
            return "mentiontracker skill is disabled. Set app.skills.mentiontracker.enabled=true.";
        if (notifier != null) notifier.notify("👁 checking for new mentions...");
        try {
            List<String> kws = brandKeywords == null || brandKeywords.isBlank() ? List.of()
                    : Arrays.stream(brandKeywords.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            List<String> srcs = sources == null || sources.isBlank() ? List.of()
                    : Arrays.stream(sources.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            if (srcs.isEmpty())
                return "No source URLs supplied. Provide RSS feeds (e.g. Google Alerts, Reddit search, HN Algolia).";
            Map<String, Object> r = mentionTracker.poll(kws, srcs);
            StringBuilder sb = new StringBuilder("👁 mentions check\n");
            sb.append("  new: ").append(r.get("newMentionCount")).append("\n");
            sb.append("  positive: ").append(r.get("positive"))
                    .append(" · negative: ").append(r.get("negative"))
                    .append(" · neutral: ").append(r.get("neutral")).append("\n");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> nm = (List<Map<String, Object>>) r.get("newMentions");
            if (nm != null && !nm.isEmpty()) {
                sb.append("\nLatest:\n");
                int n = Math.min(5, nm.size());
                for (int i = 0; i < n; i++) {
                    Map<String, Object> m = nm.get(i);
                    sb.append("  • [").append(m.get("sentiment")).append("] ")
                            .append(truncate(String.valueOf(m.get("title")), 80));
                    if (m.get("url") != null) sb.append("\n    ").append(m.get("url"));
                    sb.append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "Failed to check mentions: " + e.getMessage();
        }
    }

    @Tool(description = "Draft a markdown blog article skeleton (frontmatter + outline + FAQ + CTA). "
            + "Use when the user says 'write a blog post about X', 'draft an article on X', "
            + "'create SEO content for keyword X'. Returns a structured starting point — not a "
            + "finished article — saved to memory/blog/.")
    public String draftBlogPost(
            @ToolParam(description = "Primary keyword the article should rank for, e.g. 'desktop chatbot'")
            String primaryKeyword,
            @ToolParam(description = "Supporting keywords / subtopics, comma-separated. Optional.", required = false)
            String supportingKeywords,
            @ToolParam(description = "Target audience, e.g. 'indie hackers'. Optional.", required = false)
            String audience,
            @ToolParam(description = "CTA paragraph at the end of the article. Optional.", required = false)
            String productCta) {
        if (blogWriter == null || blogWriterProps == null)
            return "blogwriter skill is not loaded.";
        if (!blogWriterProps.isEnabled())
            return "blogwriter skill is disabled. Set app.skills.blogwriter.enabled=true.";
        if (notifier != null) notifier.notify("📝 drafting blog post about " + primaryKeyword + "...");
        try {
            List<String> sk = supportingKeywords == null || supportingKeywords.isBlank() ? List.of()
                    : Arrays.stream(supportingKeywords.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            Map<String, Object> r = blogWriter.draft(primaryKeyword, sk, audience, productCta, List.of());
            return "📝 article drafted\n"
                    + "  title: " + r.get("title") + "\n"
                    + "  slug: " + r.get("slug") + "\n"
                    + "  saved: " + r.get("storedAt") + "\n"
                    + "  word count (skeleton): " + r.get("wordCountEstimate") + "\n"
                    + "\nNext: open the file, finish the prose, publish.";
        } catch (Exception e) {
            return "Failed to draft blog post: " + e.getMessage();
        }
    }
}
