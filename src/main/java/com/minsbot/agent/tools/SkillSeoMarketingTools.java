package com.minsbot.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minsbot.skills.abtestcalc.AbTestCalcConfig;
import com.minsbot.skills.abtestcalc.AbTestCalcService;
import com.minsbot.skills.charcounter.CharCounterConfig;
import com.minsbot.skills.charcounter.CharCounterService;
import com.minsbot.skills.hashtagsuggest.HashtagSuggestConfig;
import com.minsbot.skills.hashtagsuggest.HashtagSuggestService;
import com.minsbot.skills.keywordextractor.KeywordExtractorConfig;
import com.minsbot.skills.keywordextractor.KeywordExtractorService;
import com.minsbot.skills.metaanalyzer.MetaAnalyzerConfig;
import com.minsbot.skills.metaanalyzer.MetaAnalyzerService;
import com.minsbot.skills.readability.ReadabilityConfig;
import com.minsbot.skills.readability.ReadabilityService;
import com.minsbot.skills.robotschecker.RobotsCheckerConfig;
import com.minsbot.skills.robotschecker.RobotsCheckerService;
import com.minsbot.skills.sitemapchecker.SitemapCheckerConfig;
import com.minsbot.skills.sitemapchecker.SitemapCheckerService;
import com.minsbot.skills.sluggenerator.SlugGeneratorConfig;
import com.minsbot.skills.sluggenerator.SlugGeneratorService;
import com.minsbot.skills.subjectanalyzer.SubjectAnalyzerConfig;
import com.minsbot.skills.subjectanalyzer.SubjectAnalyzerService;
import com.minsbot.skills.utmbuilder.UtmBuilderConfig;
import com.minsbot.skills.utmbuilder.UtmBuilderService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SkillSeoMarketingTools {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolExecutionNotifier notifier;

    private final MetaAnalyzerService meta; private final MetaAnalyzerConfig.MetaAnalyzerProperties metaProps;
    private final KeywordExtractorService kw; private final KeywordExtractorConfig.KeywordExtractorProperties kwProps;
    private final SitemapCheckerService sitemap; private final SitemapCheckerConfig.SitemapCheckerProperties sitemapProps;
    private final RobotsCheckerService robots; private final RobotsCheckerConfig.RobotsCheckerProperties robotsProps;
    private final ReadabilityService readability; private final ReadabilityConfig.ReadabilityProperties readProps;
    private final SlugGeneratorService slug; private final SlugGeneratorConfig.SlugGeneratorProperties slugProps;
    private final UtmBuilderService utm; private final UtmBuilderConfig.UtmBuilderProperties utmProps;
    private final SubjectAnalyzerService subject; private final SubjectAnalyzerConfig.SubjectAnalyzerProperties subjProps;
    private final CharCounterService chars; private final CharCounterConfig.CharCounterProperties charProps;
    private final AbTestCalcService abTest; private final AbTestCalcConfig.AbTestCalcProperties abProps;
    private final HashtagSuggestService hashtag; private final HashtagSuggestConfig.HashtagSuggestProperties hashtagProps;

    public SkillSeoMarketingTools(ToolExecutionNotifier notifier,
                                  MetaAnalyzerService meta, MetaAnalyzerConfig.MetaAnalyzerProperties metaProps,
                                  KeywordExtractorService kw, KeywordExtractorConfig.KeywordExtractorProperties kwProps,
                                  SitemapCheckerService sitemap, SitemapCheckerConfig.SitemapCheckerProperties sitemapProps,
                                  RobotsCheckerService robots, RobotsCheckerConfig.RobotsCheckerProperties robotsProps,
                                  ReadabilityService readability, ReadabilityConfig.ReadabilityProperties readProps,
                                  SlugGeneratorService slug, SlugGeneratorConfig.SlugGeneratorProperties slugProps,
                                  UtmBuilderService utm, UtmBuilderConfig.UtmBuilderProperties utmProps,
                                  SubjectAnalyzerService subject, SubjectAnalyzerConfig.SubjectAnalyzerProperties subjProps,
                                  CharCounterService chars, CharCounterConfig.CharCounterProperties charProps,
                                  AbTestCalcService abTest, AbTestCalcConfig.AbTestCalcProperties abProps,
                                  HashtagSuggestService hashtag, HashtagSuggestConfig.HashtagSuggestProperties hashtagProps) {
        this.notifier = notifier;
        this.meta = meta; this.metaProps = metaProps;
        this.kw = kw; this.kwProps = kwProps;
        this.sitemap = sitemap; this.sitemapProps = sitemapProps;
        this.robots = robots; this.robotsProps = robotsProps;
        this.readability = readability; this.readProps = readProps;
        this.slug = slug; this.slugProps = slugProps;
        this.utm = utm; this.utmProps = utmProps;
        this.subject = subject; this.subjProps = subjProps;
        this.chars = chars; this.charProps = charProps;
        this.abTest = abTest; this.abProps = abProps;
        this.hashtag = hashtag; this.hashtagProps = hashtagProps;
    }

    @Tool(description = "Analyze the SEO meta tags of a URL: title, description, og:, canonical, h1. Returns issues found.")
    public String analyzeMeta(@ToolParam(description = "Full URL to analyze") String url) {
        if (!metaProps.isEnabled()) return disabled("metaanalyzer");
        notifier.notify("Analyzing meta tags for: " + url);
        try { return toJson(meta.analyze(url)); } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Extract top keywords/phrases from text or a URL. Returns frequency-ranked terms.")
    public String extractKeywords(
            @ToolParam(description = "Text content OR a URL (auto-detected)") String textOrUrl,
            @ToolParam(description = "Top N terms to return (default 20)") double topN) {
        if (!kwProps.isEnabled()) return disabled("keywordextractor");
        int n = Math.max(1, (int) topN);
        try {
            if (textOrUrl.startsWith("http")) return toJson(kw.extractFromUrl(textOrUrl, n, 2, false));
            return toJson(kw.extractFromText(textOrUrl, n, 2, false));
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Check a sitemap.xml: URL count, duplicates, optional HEAD status check.")
    public String checkSitemap(
            @ToolParam(description = "Sitemap URL (e.g. https://example.com/sitemap.xml)") String sitemapUrl,
            @ToolParam(description = "Whether to HEAD-check URLs for 200/404 status (slower)") boolean checkStatus) {
        if (!sitemapProps.isEnabled()) return disabled("sitemapchecker");
        try { return toJson(sitemap.check(sitemapUrl, checkStatus)); } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Check a robots.txt: parse rules and test whether a specific path is allowed for a user-agent.")
    public String checkRobots(
            @ToolParam(description = "robots.txt URL") String url,
            @ToolParam(description = "Path to test (e.g. '/admin') — empty string to just parse") String path,
            @ToolParam(description = "User-agent to test against (use '*' for default)") String userAgent) {
        if (!robotsProps.isEnabled()) return disabled("robotschecker");
        try {
            if (path == null || path.isEmpty()) return toJson(robots.parse(url));
            return toJson(robots.check(url, path, userAgent));
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Analyze text readability: Flesch reading ease, FK grade, Gunning Fog, word/sentence counts.")
    public String analyzeReadability(@ToolParam(description = "Text to analyze") String text) {
        if (!readProps.isEnabled()) return disabled("readability");
        try { return toJson(readability.analyze(text, readProps.getMaxTextChars())); } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Generate an SEO-friendly URL slug from a title or phrase.")
    public String generateSlug(
            @ToolParam(description = "Input title or phrase") String input,
            @ToolParam(description = "Whether to strip stopwords (the, and, of, etc.)") boolean stripStopwords) {
        if (!slugProps.isEnabled()) return disabled("sluggenerator");
        return slug.slugify(input, "-", true, stripStopwords, slugProps.getDefaultMaxSlugLength());
    }

    @Tool(description = "Build a UTM-tagged URL for campaign tracking. utmMedium is e.g. 'email', 'social', 'cpc'.")
    public String buildUtmUrl(
            @ToolParam(description = "Base URL") String baseUrl,
            @ToolParam(description = "utm_source (e.g. 'newsletter', 'twitter')") String source,
            @ToolParam(description = "utm_medium (e.g. 'email', 'social')") String medium,
            @ToolParam(description = "utm_campaign (e.g. 'black-friday-2026')") String campaign) {
        if (!utmProps.isEnabled()) return disabled("utmbuilder");
        Map<String, String> utms = new LinkedHashMap<>();
        utms.put("utm_source", source);
        utms.put("utm_medium", medium);
        utms.put("utm_campaign", campaign);
        try { return toJson(utm.build(baseUrl, utms)); } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Score an email subject line: length, spam triggers, caps, emoji, urgency, power words.")
    public String analyzeEmailSubject(@ToolParam(description = "Email subject line") String subjectLine) {
        if (!subjProps.isEnabled()) return disabled("subjectanalyzer");
        try { return toJson(subject.analyze(subjectLine)); } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Check if text fits within character limits for various social platforms (Twitter/X, LinkedIn, Instagram, etc.).")
    public String checkCharLimits(@ToolParam(description = "Text to check") String text) {
        if (!charProps.isEnabled()) return disabled("charcounter");
        try { return toJson(chars.count(text, null)); } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Calculate A/B test statistical significance (two-proportion z-test). Returns z-score, p-value, confidence%, winner.")
    public String abTestSignificance(
            @ToolParam(description = "Variant A visitors") double visitorsA,
            @ToolParam(description = "Variant A conversions") double conversionsA,
            @ToolParam(description = "Variant B visitors") double visitorsB,
            @ToolParam(description = "Variant B conversions") double conversionsB) {
        if (!abProps.isEnabled()) return disabled("abtestcalc");
        try { return toJson(abTest.significance((long) visitorsA, (long) conversionsA, (long) visitorsB, (long) conversionsB)); }
        catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Suggest hashtags extracted from content (plus any already present in the text).")
    public String suggestHashtags(
            @ToolParam(description = "Content text") String text,
            @ToolParam(description = "How many hashtags to return (default 15)") double topN) {
        if (!hashtagProps.isEnabled()) return disabled("hashtagsuggest");
        try { return toJson(hashtag.suggest(text, Math.max(1, (int) topN), true)); }
        catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private String disabled(String name) { return "Skill '" + name + "' is disabled. Enable via app.skills." + name + ".enabled=true"; }
    private String toJson(Object obj) { try { return mapper.writeValueAsString(obj); } catch (Exception e) { return String.valueOf(obj); } }
}
