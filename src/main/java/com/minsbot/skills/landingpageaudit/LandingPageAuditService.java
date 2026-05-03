package com.minsbot.skills.landingpageaudit;

import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Audits a landing page URL: meta tags, H1, single-CTA dominance, page weight,
 * mobile viewport, form complexity, social-proof signals. Returns prioritized fixes.
 */
@Service
public class LandingPageAuditService {

    private final LandingPageAuditConfig.LandingPageAuditProperties props;
    private final HttpClient http;

    private static final Pattern H1 = Pattern.compile("<h1\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE = Pattern.compile(
            "<title\\b[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern META_DESC = Pattern.compile(
            "<meta\\b[^>]*name\\s*=\\s*\"description\"[^>]*content\\s*=\\s*\"([^\"]*)\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VIEWPORT = Pattern.compile(
            "<meta\\b[^>]*name\\s*=\\s*\"viewport\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern OG_IMAGE = Pattern.compile(
            "<meta\\b[^>]*property\\s*=\\s*\"og:image\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern FAVICON = Pattern.compile(
            "<link\\b[^>]*rel\\s*=\\s*\"(?:icon|shortcut icon)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern FORM = Pattern.compile("<form\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern INPUT = Pattern.compile(
            "<input\\b[^>]*type\\s*=\\s*\"(?!hidden|submit|button)([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern BUTTON_OR_CTA = Pattern.compile(
            "<(?:button|a)\\b[^>]*>([^<]{2,80})</(?:button|a)>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CTA_TEXT = Pattern.compile(
            "\\b(sign up|get started|try free|start free|buy|book|demo|subscribe|download|join|register)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SOCIAL_PROOF = Pattern.compile(
            "\\b(trusted by|customers|reviews?|rating|stars|case stud|testimonial|as seen in|featured in|users|companies)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SCRIPT = Pattern.compile("<script\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMG = Pattern.compile("<img\\b", Pattern.CASE_INSENSITIVE);

    public LandingPageAuditService(LandingPageAuditConfig.LandingPageAuditProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    public Map<String, Object> audit(String url) throws Exception {
        long t0 = System.currentTimeMillis();
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .timeout(Duration.ofMillis(props.getTimeoutMs()))
                .header("User-Agent", props.getUserAgent()).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        long ms = System.currentTimeMillis() - t0;
        String body = resp.body();
        int bytes = body == null ? 0 : body.getBytes().length;
        if (body != null && body.length() > props.getMaxFetchBytes())
            body = body.substring(0, props.getMaxFetchBytes());
        if (body == null) body = "";

        String title = firstGroup(TITLE, body);
        String desc = firstAttr(META_DESC, body);
        int h1Count = count(H1, body);
        int formCount = count(FORM, body);
        int inputCount = count(INPUT, body);
        boolean viewport = VIEWPORT.matcher(body).find();
        boolean ogImage = OG_IMAGE.matcher(body).find();
        boolean favicon = FAVICON.matcher(body).find();
        int scripts = count(SCRIPT, body);
        int images = count(IMG, body);

        List<String> ctaTexts = new ArrayList<>();
        Matcher bm = BUTTON_OR_CTA.matcher(body);
        while (bm.find() && ctaTexts.size() < 80) {
            String txt = bm.group(1).replaceAll("<[^>]+>", "").trim();
            if (CTA_TEXT.matcher(txt).find()) ctaTexts.add(txt);
        }
        Set<String> distinctCtas = new LinkedHashSet<>(ctaTexts.stream().map(String::toLowerCase).toList());
        boolean socialProof = SOCIAL_PROOF.matcher(body).find();

        int score = 100;
        List<String> issues = new ArrayList<>();
        if (resp.statusCode() != 200) { score -= 30; issues.add("HTTP " + resp.statusCode()); }
        if (title.isBlank()) { score -= 10; issues.add("Missing <title>"); }
        else if (title.length() > 65) { score -= 3; issues.add("Title >65 chars (truncated in SERP)"); }
        if (desc.isBlank()) { score -= 8; issues.add("Missing meta description"); }
        if (h1Count == 0) { score -= 10; issues.add("No <h1> on page"); }
        else if (h1Count > 1) { score -= 5; issues.add("Multiple <h1> tags (" + h1Count + ") — pick one hero"); }
        if (!viewport) { score -= 10; issues.add("Missing mobile viewport meta — page won't render right on phones"); }
        if (!ogImage) { score -= 3; issues.add("Missing og:image — bad social previews"); }
        if (!favicon) { score -= 2; issues.add("Missing favicon"); }
        if (distinctCtas.isEmpty()) { score -= 15; issues.add("No primary CTA detected"); }
        else if (distinctCtas.size() > 3) { score -= 8; issues.add("Too many distinct CTAs (" + distinctCtas.size() + ") — pick ONE primary"); }
        if (inputCount > 5) { score -= 5; issues.add("Form has " + inputCount + " visible fields — drop to 3 if possible"); }
        if (!socialProof) { score -= 8; issues.add("No social proof signals (testimonials, logos, ratings)"); }
        if (bytes > 1_500_000) { score -= 8; issues.add("Page weight " + (bytes / 1024) + "KB — heavy"); }
        if (scripts > 30) { score -= 5; issues.add(scripts + " script tags — consolidate"); }
        if (ms > 3000) { score -= 5; issues.add("Server response " + ms + "ms — slow"); }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skill", "landingpageaudit");
        result.put("url", url);
        result.put("status", resp.statusCode());
        result.put("responseMs", ms);
        result.put("pageBytes", bytes);
        result.put("title", title);
        result.put("metaDescription", desc);
        result.put("h1Count", h1Count);
        result.put("hasViewport", viewport);
        result.put("hasOgImage", ogImage);
        result.put("hasFavicon", favicon);
        result.put("forms", formCount);
        result.put("formFields", inputCount);
        result.put("scripts", scripts);
        result.put("images", images);
        result.put("ctas", new ArrayList<>(distinctCtas));
        result.put("hasSocialProof", socialProof);
        result.put("score", Math.max(0, score));
        result.put("issues", issues);
        return result;
    }

    private static int count(Pattern p, String s) {
        Matcher m = p.matcher(s); int n = 0; while (m.find()) n++; return n;
    }
    private static String firstGroup(Pattern p, String s) {
        Matcher m = p.matcher(s); return m.find() ? m.group(1).trim() : "";
    }
    private static String firstAttr(Pattern p, String s) {
        Matcher m = p.matcher(s); return m.find() ? m.group(1).trim() : "";
    }
}
