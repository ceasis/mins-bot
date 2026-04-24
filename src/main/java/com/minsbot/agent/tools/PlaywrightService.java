package com.minsbot.agent.tools;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.microsoft.playwright.options.ScreenshotType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages a shared headless Chromium browser instance via Playwright.
 * Lazily initialized on first use. Auto-installs Chromium if not found.
 * The browser persists for the app's lifetime to avoid re-launching overhead.
 */
@Service
public class PlaywrightService {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightService.class);
    private static final Path BASE_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data");

    private Playwright playwright;
    private Browser browser;
    private final Object lock = new Object();
    private volatile boolean installAttempted = false;

    // ─── Persistent viewer page (for browser tab) ──────────────────────────
    private BrowserContext viewerContext;
    private Page viewerPage;
    private volatile String lastUrl = "";
    private volatile String lastTitle = "";

    /** Get or create the Playwright instance (without launching a browser). */
    private Playwright ensurePlaywright() {
        synchronized (lock) {
            if (playwright == null) {
                playwright = Playwright.create();
            }
            return playwright;
        }
    }

    /** Get (or lazily create) the shared browser. Auto-installs Chromium on first use. */
    private Browser getBrowser() {
        synchronized (lock) {
            if (browser == null || !browser.isConnected()) {
                // Try to launch — if Chromium isn't installed, install it and retry
                try {
                    browser = launchChromium(ensurePlaywright());
                } catch (Exception e) {
                    if (!installAttempted) {
                        installAttempted = true;
                        log.info("[Playwright] Chromium not found — installing automatically...");
                        installChromium();
                        browser = launchChromium(ensurePlaywright());
                    } else {
                        throw e;
                    }
                }
                log.info("[Playwright] Browser ready.");
            }
            return browser;
        }
    }

    /**
     * Connect to a running browser via Chrome DevTools Protocol.
     * Use this to connect to the user's actual Chrome/Edge instance.
     */
    public Browser connectOverCDP(String endpoint) {
        return ensurePlaywright().chromium().connectOverCDP(endpoint);
    }

    /** Launch headless Chromium with stealth/anti-detection options. */
    private Browser launchChromium(Playwright pw) {
        return pw.chromium().launch(
                new BrowserType.LaunchOptions()
                        .setHeadless(true)
                        .setArgs(List.of(
                                "--disable-gpu",
                                "--no-sandbox",
                                "--disable-dev-shm-usage",
                                // ─── Stealth / anti-bot detection ───
                                "--disable-blink-features=AutomationControlled",
                                "--disable-features=IsolateOrigins,site-per-process",
                                "--disable-infobars",
                                "--window-size=1920,1080",
                                "--start-maximized",
                                "--disable-extensions",
                                "--disable-popup-blocking",
                                "--disable-background-networking",
                                "--metrics-recording-only",
                                "--no-first-run",
                                "--disable-default-apps"
                        ))
        );
    }

    /** Run Playwright CLI to install Chromium browser binaries. */
    private void installChromium() {
        try {
            log.info("[Playwright] Running: install chromium (this may take a minute on first run)...");
            // Playwright Java bundles a CLI that handles browser downloads
            com.microsoft.playwright.CLI.main(new String[]{"install", "chromium"});
            log.info("[Playwright] Chromium installed successfully.");
        } catch (Exception e) {
            log.error("[Playwright] Failed to install Chromium: {}", e.getMessage(), e);
            throw new RuntimeException("Playwright Chromium install failed: " + e.getMessage(), e);
        }
    }

    // Realistic Chrome UA — update periodically to match latest stable Chrome
    private static final String CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    /** Stealth JS injected into every new context to evade bot detection. */
    private static final String STEALTH_JS = """
            // Hide webdriver flag
            Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
            // Fake plugins array (real Chrome always has at least 3)
            Object.defineProperty(navigator, 'plugins', {
                get: () => [
                    { name: 'Chrome PDF Plugin', filename: 'internal-pdf-viewer' },
                    { name: 'Chrome PDF Viewer', filename: 'mhjfbmdgcfjbbpaeojofohoefgiehjai' },
                    { name: 'Native Client', filename: 'internal-nacl-plugin' }
                ]
            });
            // Fake languages
            Object.defineProperty(navigator, 'languages', { get: () => ['en-US', 'en'] });
            // Override chrome.runtime to look like a real browser
            window.chrome = { runtime: {}, loadTimes: function(){}, csi: function(){} };
            // Remove Playwright-specific markers
            delete window.__playwright;
            delete window.__pw_manual;
            """;

    /** Create a fresh browser context + page with stealth defaults. */
    private Page newPage() {
        BrowserContext context = getBrowser().newContext(
                new Browser.NewContextOptions()
                        .setUserAgent(CHROME_UA)
                        .setViewportSize(1920, 1080)
                        .setLocale("en-US")
                        .setTimezoneId("America/New_York")
        );
        context.setDefaultTimeout(30000);
        context.addInitScript(STEALTH_JS);
        return context.newPage();
    }

    /**
     * Render a webpage as a PDF to the given absolute path. Waits for network-idle
     * so lazy-loaded content appears. Returns the output path on success.
     */
    public String renderToPdf(String url, String outputPath, String format, boolean landscape) {
        try (Page page = newPage()) {
            page.navigate(url);
            page.waitForLoadState(LoadState.NETWORKIDLE);
            // Small extra settle for fonts/images
            page.waitForTimeout(500);
            java.nio.file.Path out = java.nio.file.Path.of(outputPath);
            if (out.getParent() != null) java.nio.file.Files.createDirectories(out.getParent());
            Page.PdfOptions opts = new Page.PdfOptions()
                    .setPath(out)
                    .setFormat(format != null && !format.isBlank() ? format : "Letter")
                    .setLandscape(landscape)
                    .setPrintBackground(true)
                    .setMargin(new com.microsoft.playwright.options.Margin()
                            .setTop("0.5in").setBottom("0.5in")
                            .setLeft("0.5in").setRight("0.5in"));
            page.pdf(opts);
            page.context().close();
            return out.toString();
        } catch (Exception e) {
            throw new RuntimeException("renderToPdf failed: " + e.getMessage(), e);
        }
    }

    /**
     * Navigate to a URL and collect any browser console errors AND uncaught page errors
     * during page load and the configured settle window. Used by the QA test pipeline
     * to decide whether a freshly-launched app's UI is silently broken.
     *
     * @return a structured report: "CONSOLE ERRORS:" then each error line, "PAGE ERRORS:"
     *         then each uncaught exception, plus a final summary like
     *         "OK — no console or page errors." when clean.
     */
    public String probeConsoleErrors(String url, int waitSeconds) {
        java.util.List<String> consoleErrors = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.List<String> pageErrors = new java.util.concurrent.CopyOnWriteArrayList<>();
        int wait = Math.max(1, Math.min(30, waitSeconds));
        try (Page page = newPage()) {
            page.onConsoleMessage(msg -> {
                if ("error".equalsIgnoreCase(msg.type())) {
                    consoleErrors.add(msg.text());
                }
            });
            page.onPageError(err -> pageErrors.add(err));
            try {
                page.navigate(url);
            } catch (Exception navEx) {
                return "NAVIGATION FAILED: " + navEx.getMessage();
            }
            try { page.waitForLoadState(LoadState.NETWORKIDLE); } catch (Exception ignored) {}
            try { Thread.sleep(wait * 1000L); } catch (InterruptedException ignored) {}
            page.context().close();
        } catch (Exception e) {
            return "PROBE ERROR: " + e.getMessage();
        }
        StringBuilder sb = new StringBuilder();
        if (!consoleErrors.isEmpty()) {
            sb.append("CONSOLE ERRORS (").append(consoleErrors.size()).append("):\n");
            for (String m : consoleErrors) sb.append("  ").append(m).append('\n');
        }
        if (!pageErrors.isEmpty()) {
            sb.append("PAGE ERRORS (").append(pageErrors.size()).append("):\n");
            for (String m : pageErrors) sb.append("  ").append(m).append('\n');
        }
        if (sb.length() == 0) return "OK — no console or page errors.";
        return sb.toString().stripTrailing();
    }

    /**
     * Log in via a standard HTML form, then navigate a list of post-login URLs and
     * capture any console/page errors from each one. Auto-detects common input
     * selectors (username / email / user / login for the user field; password /
     * pass for the password field) and submits via the first submit button.
     *
     * @return a multi-line report: per-URL status + any errors found, plus a final
     *         LOGIN OK / LOGIN FAILED line.
     */
    public String loginAndProbe(String loginUrl,
                                String username,
                                String password,
                                java.util.List<String> postLoginUrls,
                                int waitSecondsPerPage) {
        java.util.List<String> allConsoleErrors = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.List<String> allPageErrors = new java.util.concurrent.CopyOnWriteArrayList<>();
        int wait = Math.max(1, Math.min(30, waitSecondsPerPage));
        StringBuilder report = new StringBuilder();

        try (Page page = newPage()) {
            page.onConsoleMessage(msg -> {
                if ("error".equalsIgnoreCase(msg.type())) allConsoleErrors.add("[" + page.url() + "] " + msg.text());
            });
            page.onPageError(err -> allPageErrors.add("[" + page.url() + "] " + err));

            // ─── Login ────────────────────────────────────────────────
            report.append("── login flow @ ").append(loginUrl).append(" ──\n");
            try { page.navigate(loginUrl); }
            catch (Exception e) { return "NAVIGATE to login failed: " + e.getMessage(); }
            try { page.waitForLoadState(LoadState.NETWORKIDLE); } catch (Exception ignored) {}

            String userSelector = firstMatchingSelector(page, new String[]{
                    "input[name=username]", "input[name=user]", "input[name=email]",
                    "input[name=login]", "input[id=username]", "input[id=email]",
                    "input[type=email]"
            });
            String passSelector = firstMatchingSelector(page, new String[]{
                    "input[name=password]", "input[name=pass]",
                    "input[id=password]", "input[type=password]"
            });
            String submitSelector = firstMatchingSelector(page, new String[]{
                    "button[type=submit]", "input[type=submit]",
                    "button:has-text(\"Log in\")", "button:has-text(\"Login\")",
                    "button:has-text(\"Sign in\")"
            });

            if (userSelector == null || passSelector == null) {
                report.append("  ✗ Could not find login form fields on ").append(loginUrl).append('\n');
                report.append("    userSel=").append(userSelector).append(" passSel=").append(passSelector).append('\n');
                return report.toString();
            }
            report.append("  username field: ").append(userSelector).append('\n');
            report.append("  password field: ").append(passSelector).append('\n');
            try {
                page.fill(userSelector, username == null ? "" : username);
                page.fill(passSelector, password == null ? "" : password);
            } catch (Exception e) {
                report.append("  ✗ fill failed: ").append(e.getMessage()).append('\n');
                return report.toString();
            }
            try {
                if (submitSelector != null) page.click(submitSelector);
                else page.press(passSelector, "Enter");
            } catch (Exception e) {
                report.append("  ✗ submit failed: ").append(e.getMessage()).append('\n');
                return report.toString();
            }
            try { page.waitForLoadState(LoadState.NETWORKIDLE); } catch (Exception ignored) {}
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}

            String finalUrl = page.url();
            boolean stillOnLogin = finalUrl.toLowerCase().contains("login")
                    || finalUrl.equalsIgnoreCase(loginUrl);
            boolean loginError = page.locator("text=/invalid|incorrect|error|failed/i").count() > 0
                    && stillOnLogin;
            if (stillOnLogin || loginError) {
                report.append("  ✗ LOGIN FAILED — still on login page at ").append(finalUrl).append('\n');
                if (!allConsoleErrors.isEmpty()) {
                    report.append("  console errors during login:\n");
                    for (String m : allConsoleErrors) report.append("    ").append(m).append('\n');
                }
                return report.toString();
            }
            report.append("  ✓ LOGIN OK — landed at ").append(finalUrl).append('\n');

            // ─── Post-login probes ──────────────────────────────────
            if (postLoginUrls == null || postLoginUrls.isEmpty()) {
                report.append("\n(no post-login URLs provided — only login was verified)");
                return report.toString();
            }

            report.append("\n── post-login probes ──\n");
            for (String url : postLoginUrls) {
                int errsBefore = allConsoleErrors.size() + allPageErrors.size();
                try {
                    page.navigate(url);
                    page.waitForLoadState(LoadState.NETWORKIDLE);
                    Thread.sleep(wait * 1000L);
                    int errsAfter = allConsoleErrors.size() + allPageErrors.size();
                    int delta = errsAfter - errsBefore;
                    String mark = delta == 0 ? "✓" : "✗";
                    report.append("  ").append(mark).append(" ").append(url)
                          .append(" → ").append(page.url()).append(" (")
                          .append(delta).append(" new error(s))\n");
                } catch (Exception e) {
                    report.append("  ✗ ").append(url).append(" → navigate error: ").append(e.getMessage()).append('\n');
                }
            }

            // ─── Summary ────────────────────────────────────────────
            if (!allConsoleErrors.isEmpty() || !allPageErrors.isEmpty()) {
                report.append("\nCONSOLE ERRORS (").append(allConsoleErrors.size()).append("):\n");
                for (String m : allConsoleErrors) report.append("  ").append(m).append('\n');
                if (!allPageErrors.isEmpty()) {
                    report.append("PAGE ERRORS (").append(allPageErrors.size()).append("):\n");
                    for (String m : allPageErrors) report.append("  ").append(m).append('\n');
                }
            } else {
                report.append("\n✓ No console or page errors across all probed URLs.");
            }
            return report.toString();
        } catch (Exception e) {
            return "PROBE ERROR: " + e.getMessage();
        }
    }

    private static String firstMatchingSelector(Page page, String[] candidates) {
        for (String sel : candidates) {
            try { if (page.locator(sel).count() > 0) return sel; } catch (Exception ignored) {}
        }
        return null;
    }

    /** Known device viewports for responsive testing. */
    public static final java.util.Map<String, int[]> DEVICE_VIEWPORTS = java.util.Map.ofEntries(
            java.util.Map.entry("desktop-fhd",   new int[]{1920, 1080}),
            java.util.Map.entry("desktop",       new int[]{1440,  900}),
            java.util.Map.entry("laptop",        new int[]{1366,  768}),
            java.util.Map.entry("tablet",        new int[]{ 768, 1024}),
            java.util.Map.entry("tablet-lg",     new int[]{1024, 1366}),
            java.util.Map.entry("mobile",        new int[]{ 390,  844}),
            java.util.Map.entry("mobile-lg",     new int[]{ 414,  896}),
            java.util.Map.entry("mobile-sm",     new int[]{ 360,  640})
    );

    /**
     * Open {@code url} at a specific viewport, capture a screenshot, return the
     * bytes. Creates a throwaway page with the given viewport size so responsive
     * breakpoints render correctly. Returns null on failure.
     */
    public byte[] screenshotAtViewport(String url, int width, int height, int waitSeconds) {
        int wait = Math.max(1, Math.min(20, waitSeconds));
        try {
            com.microsoft.playwright.BrowserContext ctx = getBrowser().newContext(
                    new com.microsoft.playwright.Browser.NewContextOptions()
                            .setViewportSize(width, height));
            try (Page page = ctx.newPage()) {
                page.navigate(url, new com.microsoft.playwright.Page.NavigateOptions()
                        .setTimeout(15_000));
                try { page.waitForLoadState(LoadState.NETWORKIDLE); } catch (Exception ignored) {}
                try { Thread.sleep(wait * 1000L); } catch (InterruptedException ignored) {}
                byte[] png = page.screenshot(new com.microsoft.playwright.Page.ScreenshotOptions()
                        .setFullPage(true));
                ctx.close();
                return png;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Load a URL, record every network request made during load + settle, and
     * return a summary with any 4xx/5xx responses flagged. Detects "page looks
     * fine but a background XHR silently 500'd" bugs.
     */
    public String networkTrace(String url, int waitSeconds) {
        int wait = Math.max(1, Math.min(20, waitSeconds));
        java.util.List<String> reqs = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.List<String> failed = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (Page page = newPage()) {
            page.onResponse(resp -> {
                try {
                    int code = resp.status();
                    String line = code + " " + resp.request().method() + " " + resp.url();
                    reqs.add(line);
                    if (code >= 400) failed.add(line);
                } catch (Exception ignored) {}
            });
            page.onRequestFailed(req -> failed.add("FAIL " + req.method() + " " + req.url()
                    + " — " + req.failure()));
            try { page.navigate(url); } catch (Exception e) { return "NAVIGATE failed: " + e.getMessage(); }
            try { page.waitForLoadState(LoadState.NETWORKIDLE); } catch (Exception ignored) {}
            try { Thread.sleep(wait * 1000L); } catch (InterruptedException ignored) {}
            page.context().close();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("requests: ").append(reqs.size())
          .append(" | failing: ").append(failed.size()).append('\n');
        if (!failed.isEmpty()) {
            sb.append("FAILING REQUESTS:\n");
            for (String l : failed) sb.append("  ").append(l).append('\n');
        } else {
            sb.append("✓ No failing requests.");
        }
        return sb.toString().stripTrailing();
    }

    /**
     * Inject axe-core from the CDN and run an accessibility audit on the loaded
     * page. Returns the violations grouped by impact. Best-effort — requires
     * internet for the axe CDN fetch.
     */
    public String accessibilityAudit(String url, int waitSeconds) {
        int wait = Math.max(1, Math.min(20, waitSeconds));
        try (Page page = newPage()) {
            try { page.navigate(url); } catch (Exception e) { return "NAVIGATE failed: " + e.getMessage(); }
            try { page.waitForLoadState(LoadState.NETWORKIDLE); } catch (Exception ignored) {}
            try { Thread.sleep(wait * 1000L); } catch (InterruptedException ignored) {}
            // Inject axe-core.
            try {
                page.addScriptTag(new com.microsoft.playwright.Page.AddScriptTagOptions()
                        .setUrl("https://cdnjs.cloudflare.com/ajax/libs/axe-core/4.9.1/axe.min.js"));
            } catch (Exception e) {
                return "axe-core inject failed: " + e.getMessage();
            }
            Object result = page.evaluate(
                    "async () => { const r = await axe.run(); " +
                    "return JSON.stringify(r.violations.map(v => ({ id: v.id, impact: v.impact, " +
                    "help: v.help, nodes: v.nodes.length }))); }");
            String json = String.valueOf(result);
            page.context().close();
            if (json == null || json.isBlank() || json.equals("[]")) return "✓ No accessibility violations.";
            // Trivial count by impact.
            int critical = countOccurrences(json, "\"impact\":\"critical\"");
            int serious  = countOccurrences(json, "\"impact\":\"serious\"");
            int moderate = countOccurrences(json, "\"impact\":\"moderate\"");
            int minor    = countOccurrences(json, "\"impact\":\"minor\"");
            return "a11y: critical=" + critical + " serious=" + serious
                 + " moderate=" + moderate + " minor=" + minor + "\n" + json;
        } catch (Exception e) {
            return "accessibilityAudit error: " + e.getMessage();
        }
    }

    private static int countOccurrences(String hay, String needle) {
        if (hay == null) return 0; int c = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) { c++; i += needle.length(); }
        return c;
    }

    /**
     * Find every {@code <form>} on the page, fill its inputs with reasonable dummy
     * data, submit each, and record whether the post-submit page looks like an
     * error (5xx, exception trace, "error" text). Smoke-only — doesn't try to
     * satisfy complex validation.
     */
    public String smokeTestForms(String url, int waitSeconds) {
        int wait = Math.max(1, Math.min(10, waitSeconds));
        StringBuilder sb = new StringBuilder();
        try (Page page = newPage()) {
            try { page.navigate(url); } catch (Exception e) { return "NAVIGATE failed: " + e.getMessage(); }
            try { page.waitForLoadState(LoadState.NETWORKIDLE); } catch (Exception ignored) {}
            int formCount = page.locator("form").count();
            sb.append("forms found: ").append(formCount).append('\n');
            if (formCount == 0) return sb.append("(nothing to smoke)").toString().stripTrailing();

            for (int i = 0; i < formCount; i++) {
                try {
                    var form = page.locator("form").nth(i);
                    // Fill text-ish inputs.
                    int inputs = form.locator("input[type=text], input[type=email], input[type=password], " +
                            "input[type=number], input:not([type]), textarea").count();
                    for (int j = 0; j < inputs; j++) {
                        var in = form.locator("input[type=text], input[type=email], input[type=password], " +
                                "input[type=number], input:not([type]), textarea").nth(j);
                        String type = String.valueOf(in.getAttribute("type"));
                        String val;
                        if ("email".equalsIgnoreCase(type)) val = "qa@example.com";
                        else if ("password".equalsIgnoreCase(type)) val = "TestPass123!";
                        else if ("number".equalsIgnoreCase(type)) val = "42";
                        else val = "QA test " + System.currentTimeMillis();
                        try { in.fill(val); } catch (Exception ignored) {}
                    }
                    String before = page.url();
                    try {
                        form.locator("button[type=submit], input[type=submit]").first().click(
                                new com.microsoft.playwright.Locator.ClickOptions().setTimeout(5000));
                    } catch (Exception e) {
                        sb.append("  form[").append(i).append("]: submit button not found — skipped\n");
                        continue;
                    }
                    try { page.waitForLoadState(LoadState.NETWORKIDLE); } catch (Exception ignored) {}
                    try { Thread.sleep(wait * 1000L); } catch (InterruptedException ignored) {}
                    String after = page.url();
                    String bodyText = "";
                    try { bodyText = page.innerText("body"); } catch (Exception ignored) {}
                    boolean looksBroken =
                            bodyText.toLowerCase().contains("whitelabel") ||
                            bodyText.toLowerCase().contains("exception") ||
                            bodyText.toLowerCase().contains("stack trace") ||
                            bodyText.toLowerCase().contains("internal server error") ||
                            bodyText.contains(" 500 ") || bodyText.contains("status=500");
                    sb.append("  form[").append(i).append("]: ")
                      .append(looksBroken ? "✗" : "✓")
                      .append(" ").append(before).append(" → ").append(after)
                      .append(looksBroken ? " (error page detected)" : "")
                      .append('\n');
                    // Re-navigate back to the original URL for the next form.
                    page.navigate(url);
                    try { page.waitForLoadState(LoadState.NETWORKIDLE); } catch (Exception ignored) {}
                } catch (Exception e) {
                    sb.append("  form[").append(i).append("]: error — ").append(e.getMessage()).append('\n');
                }
            }
            page.context().close();
        } catch (Exception e) {
            return "smokeTestForms error: " + e.getMessage();
        }
        return sb.toString().stripTrailing();
    }

    /** Navigate to a URL, wait for content to load, and return the rendered text. */
    public String getPageText(String url) {
        try (Page page = newPage()) {
            page.navigate(url);
            page.waitForLoadState(LoadState.NETWORKIDLE);
            String text = page.innerText("body");
            page.context().close();
            mirrorToViewer(url);
            if (text.length() > 10000) {
                text = text.substring(0, 10000) + "\n... (truncated)";
            }
            return text;
        }
    }

    /** Navigate and return the full rendered HTML. */
    public String getPageHtml(String url) {
        try (Page page = newPage()) {
            page.navigate(url);
            page.waitForLoadState(LoadState.NETWORKIDLE);
            String html = page.content();
            page.context().close();
            mirrorToViewer(url);
            if (html.length() > 500000) {
                html = html.substring(0, 500000);
            }
            return html;
        }
    }

    /** Navigate to a URL and extract all image src URLs (after JS rendering). */
    public List<String> getImageUrls(String url) {
        try (Page page = newPage()) {
            page.navigate(url);
            page.waitForLoadState(LoadState.NETWORKIDLE);
            // Use JS to extract all img src attributes after rendering
            @SuppressWarnings("unchecked")
            List<String> images = (List<String>) page.evaluate("""
                    () => {
                        const imgs = document.querySelectorAll('img[src]');
                        const urls = [];
                        imgs.forEach(img => {
                            const src = img.src;
                            if (src && src.startsWith('http') && !src.includes('data:')) {
                                urls.push(src);
                            }
                        });
                        // Also grab background images
                        document.querySelectorAll('[style*="background-image"]').forEach(el => {
                            const match = el.style.backgroundImage.match(/url\\(['"]?(https?[^'"\\)]+)/);
                            if (match) urls.push(match[1]);
                        });
                        return [...new Set(urls)].slice(0, 100);
                    }
                    """);
            page.context().close();
            return images;
        }
    }

    /** Navigate and extract all anchor href links. */
    public List<String> getLinkUrls(String url) {
        try (Page page = newPage()) {
            page.navigate(url);
            page.waitForLoadState(LoadState.NETWORKIDLE);
            @SuppressWarnings("unchecked")
            List<String> links = (List<String>) page.evaluate("""
                    () => {
                        const anchors = document.querySelectorAll('a[href]');
                        const urls = [];
                        anchors.forEach(a => {
                            const href = a.href;
                            if (href && href.startsWith('http')) {
                                urls.push(href);
                            }
                        });
                        return [...new Set(urls)].slice(0, 100);
                    }
                    """);
            page.context().close();
            return links;
        }
    }

    /** Navigate to a URL, take a full-page screenshot, save to a directive folder. */
    public Path screenshotPage(String url, String directiveName) throws Exception {
        try (Page page = newPage()) {
            page.navigate(url);
            page.waitForLoadState(LoadState.NETWORKIDLE);

            String safeName = sanitizeName(directiveName);
            Path dir = BASE_DIR.resolve("directive_" + safeName);
            Files.createDirectories(dir);

            String filename = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
                    + "_page_screenshot.png";
            Path target = dir.resolve(filename);

            page.screenshot(new Page.ScreenshotOptions()
                    .setFullPage(true)
                    .setPath(target));
            page.context().close();
            mirrorToViewer(url);
            return target;
        }
    }

    /**
     * Search Google Images for a query, extract image URLs from rendered results,
     * and return them.
     */
    public List<String> searchGoogleImages(String query, int maxResults) {
        try (Page page = newPage()) {
            String searchUrl = "https://www.google.com/search?q=" +
                    java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8) +
                    "&tbm=isch";
            page.navigate(searchUrl);
            page.waitForLoadState(LoadState.NETWORKIDLE);

            // Scroll down to load more images
            for (int i = 0; i < 3; i++) {
                page.evaluate("window.scrollBy(0, window.innerHeight)");
                page.waitForTimeout(1000);
            }

            // Extract full-size image URLs from Google Images
            @SuppressWarnings("unchecked")
            List<String> images = (List<String>) page.evaluate("""
                    (max) => {
                        const urls = [];
                        // Google stores full-res URLs in data attributes or script tags
                        document.querySelectorAll('img').forEach(img => {
                            // data-src often has full res
                            const src = img.getAttribute('data-src') || img.getAttribute('data-iurl') || img.src;
                            if (src && src.startsWith('http') && !src.includes('google')
                                && !src.includes('gstatic') && !src.includes('data:')
                                && src.length > 50) {
                                urls.push(src);
                            }
                        });
                        // Also look for links containing image URLs
                        document.querySelectorAll('a[href*="imgurl="]').forEach(a => {
                            const match = a.href.match(/imgurl=([^&]+)/);
                            if (match) {
                                try { urls.push(decodeURIComponent(match[1])); } catch(e) {}
                            }
                        });
                        return [...new Set(urls)].slice(0, max);
                    }
                    """, maxResults * 2);
            page.context().close();
            return images;
        }
    }

    /**
     * Search Bing Images for a query and extract full-size image URLs.
     */
    public List<String> searchBingImages(String query, int maxResults) {
        try (Page page = newPage()) {
            String searchUrl = "https://www.bing.com/images/search?q=" +
                    java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8) +
                    "&form=HDRSC2";
            page.navigate(searchUrl);
            page.waitForLoadState(LoadState.NETWORKIDLE);

            // Scroll to load more
            for (int i = 0; i < 3; i++) {
                page.evaluate("window.scrollBy(0, window.innerHeight)");
                page.waitForTimeout(800);
            }

            // Bing stores full-res URLs in m (media URL) attribute
            @SuppressWarnings("unchecked")
            List<String> images = (List<String>) page.evaluate("""
                    (max) => {
                        const urls = [];
                        // Bing image tiles have data in 'a.iusc' elements or 'm' attribute
                        document.querySelectorAll('a.iusc').forEach(a => {
                            try {
                                const m = JSON.parse(a.getAttribute('m'));
                                if (m && m.murl) urls.push(m.murl);
                            } catch(e) {}
                        });
                        // Fallback: grab img src from thumbnails
                        if (urls.length === 0) {
                            document.querySelectorAll('img.mimg').forEach(img => {
                                const src = img.getAttribute('data-src') || img.src;
                                if (src && src.startsWith('http')) urls.push(src);
                            });
                        }
                        return [...new Set(urls)].slice(0, max);
                    }
                    """, maxResults);
            page.context().close();
            return images;
        }
    }

    /** Click an element matching the CSS selector on a page. Returns the page text after click. */
    public String clickElement(String url, String selector) {
        try (Page page = newPage()) {
            page.navigate(url);
            page.waitForLoadState(LoadState.NETWORKIDLE);
            page.click(selector);
            page.waitForLoadState(LoadState.NETWORKIDLE);
            String text = page.innerText("body");
            String currentUrl = page.url();
            page.context().close();
            if (text.length() > 8000) text = text.substring(0, 8000) + "\n... (truncated)";
            return "Navigated to: " + currentUrl + "\n\n" + text;
        }
    }

    /** Fill a form input and optionally submit. */
    public String fillAndSubmit(String url, String selector, String value, boolean submit) {
        try (Page page = newPage()) {
            page.navigate(url);
            page.waitForLoadState(LoadState.NETWORKIDLE);
            page.fill(selector, value);
            if (submit) {
                page.press(selector, "Enter");
                page.waitForLoadState(LoadState.NETWORKIDLE);
            }
            String text = page.innerText("body");
            String currentUrl = page.url();
            page.context().close();
            if (text.length() > 8000) text = text.substring(0, 8000) + "\n... (truncated)";
            return "Current URL: " + currentUrl + "\n\n" + text;
        }
    }

    // ─── Viewer API (for browser tab) ─────────────────────────────────────

    /**
     * Get or create the persistent viewer page (with stealth).
     * Uses launchPersistentContext against ~/mins_bot_data/browser_profile so cookies
     * (YouTube/Google sign-in, etc.) survive across app restarts.
     */
    public synchronized Page getViewerPage() {
        if (viewerPage == null || viewerPage.isClosed()) {
            if (viewerContext == null) {
                try {
                    java.nio.file.Path profileDir = java.nio.file.Paths.get(
                            System.getProperty("user.home"), "mins_bot_data", "browser_profile");
                    java.nio.file.Files.createDirectories(profileDir);

                    Playwright pw = ensurePlaywright();
                    viewerContext = pw.chromium().launchPersistentContext(
                            profileDir,
                            new BrowserType.LaunchPersistentContextOptions()
                                    .setHeadless(true)
                                    .setUserAgent(CHROME_UA)
                                    .setViewportSize(1280, 720)
                                    .setLocale("en-US")
                                    .setTimezoneId("America/New_York")
                                    .setArgs(List.of(
                                            "--disable-gpu",
                                            "--disable-blink-features=AutomationControlled",
                                            "--no-first-run",
                                            "--no-default-browser-check"
                                    ))
                    );
                    viewerContext.setDefaultTimeout(30000);
                    viewerContext.addInitScript(STEALTH_JS);
                    log.info("[Playwright] Viewer using persistent profile: {}", profileDir);
                } catch (Exception e) {
                    log.warn("[Playwright] Persistent context failed, falling back to transient: {}", e.getMessage());
                    viewerContext = getBrowser().newContext(
                            new Browser.NewContextOptions()
                                    .setUserAgent(CHROME_UA)
                                    .setViewportSize(1280, 720)
                                    .setLocale("en-US")
                                    .setTimezoneId("America/New_York")
                    );
                    viewerContext.setDefaultTimeout(30000);
                    viewerContext.addInitScript(STEALTH_JS);
                }
            }
            // Persistent context already has an initial page; reuse it if present
            if (!viewerContext.pages().isEmpty()) {
                viewerPage = viewerContext.pages().get(0);
            } else {
                viewerPage = viewerContext.newPage();
            }
        }
        return viewerPage;
    }

    /** Navigate the viewer page to a URL. */
    public synchronized String viewerNavigate(String url) {
        try {
            Page page = getViewerPage();
            page.navigate(url);
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            lastUrl = page.url();
            lastTitle = page.title();
            return lastTitle;
        } catch (Exception e) {
            log.warn("[Playwright] Viewer navigate failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    /** Take a JPEG screenshot of the viewer page. Returns null if no page open. */
    public synchronized byte[] viewerScreenshot() {
        if (viewerPage == null || viewerPage.isClosed()) return null;
        try {
            lastUrl = viewerPage.url();
            lastTitle = viewerPage.title();
            return viewerPage.screenshot(new Page.ScreenshotOptions()
                    .setFullPage(false)
                    .setType(ScreenshotType.JPEG)
                    .setQuality(60));
        } catch (Exception e) {
            log.debug("[Playwright] Viewer screenshot failed: {}", e.getMessage());
            return null;
        }
    }

    /** Get current URL and title of the viewer page. */
    public Map<String, String> viewerInfo() {
        return Map.of(
                "url", lastUrl,
                "title", lastTitle,
                "active", String.valueOf(viewerPage != null && !viewerPage.isClosed())
        );
    }

    /** Go back in viewer history. */
    public synchronized void viewerBack() {
        if (viewerPage == null || viewerPage.isClosed()) return;
        try {
            viewerPage.goBack();
            lastUrl = viewerPage.url();
            lastTitle = viewerPage.title();
        } catch (Exception ignored) {}
    }

    /** Go forward in viewer history. */
    public synchronized void viewerForward() {
        if (viewerPage == null || viewerPage.isClosed()) return;
        try {
            viewerPage.goForward();
            lastUrl = viewerPage.url();
            lastTitle = viewerPage.title();
        } catch (Exception ignored) {}
    }

    /** Refresh the viewer page. */
    public synchronized void viewerRefresh() {
        if (viewerPage == null || viewerPage.isClosed()) return;
        try {
            viewerPage.reload();
            lastUrl = viewerPage.url();
            lastTitle = viewerPage.title();
        } catch (Exception ignored) {}
    }

    /**
     * Search the web in the viewer page so the user can see it live in the browser tab.
     * Returns the visible text of the search results page.
     */
    public synchronized String viewerSearch(String query) {
        try {
            Page page = getViewerPage();
            String searchUrl = "https://www.google.com/search?q=" +
                    java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
            page.navigate(searchUrl);
            page.waitForLoadState(LoadState.NETWORKIDLE);
            lastUrl = page.url();
            lastTitle = page.title();
            String text = page.innerText("body");
            if (text.length() > 8000) {
                text = text.substring(0, 8000) + "\n... (truncated)";
            }
            return text;
        } catch (Exception e) {
            log.warn("[Playwright] Viewer search failed: {}", e.getMessage());
            return "Search failed: " + e.getMessage();
        }
    }

    /**
     * Search for images in the viewer page so the user sees it in the browser tab.
     * Returns a list of full-size image URLs found on the results page.
     */
    public synchronized List<String> viewerSearchImages(String query) {
        try {
            Page page = getViewerPage();
            String searchUrl = "https://www.google.com/search?q=" +
                    java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8) +
                    "&tbm=isch";
            page.navigate(searchUrl);
            page.waitForLoadState(LoadState.NETWORKIDLE);

            // Scroll to load more images
            for (int i = 0; i < 3; i++) {
                page.evaluate("window.scrollBy(0, window.innerHeight)");
                page.waitForTimeout(1000);
            }

            lastUrl = page.url();
            lastTitle = page.title();

            @SuppressWarnings("unchecked")
            List<String> images = (List<String>) page.evaluate("""
                    () => {
                        const urls = [];
                        document.querySelectorAll('img').forEach(img => {
                            const src = img.getAttribute('data-src') || img.getAttribute('data-iurl') || img.src;
                            if (src && src.startsWith('http') && !src.includes('google')
                                && !src.includes('gstatic') && !src.includes('data:')
                                && src.length > 50) {
                                urls.push(src);
                            }
                        });
                        document.querySelectorAll('a[href*="imgurl="]').forEach(a => {
                            const match = a.href.match(/imgurl=([^&]+)/);
                            if (match) {
                                try { urls.push(decodeURIComponent(match[1])); } catch(e) {}
                            }
                        });
                        return [...new Set(urls)].slice(0, 50);
                    }
                    """);
            return images;
        } catch (Exception e) {
            log.warn("[Playwright] Viewer image search failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Collect all image URLs from whatever page is currently loaded in the viewer.
     */
    public synchronized List<String> viewerCollectImages() {
        if (viewerPage == null || viewerPage.isClosed()) return List.of();
        try {
            @SuppressWarnings("unchecked")
            List<String> images = (List<String>) viewerPage.evaluate("""
                    () => {
                        const urls = [];
                        document.querySelectorAll('img[src]').forEach(img => {
                            const src = img.src;
                            if (src && src.startsWith('http') && !src.includes('data:')) {
                                urls.push(src);
                            }
                        });
                        document.querySelectorAll('[style*="background-image"]').forEach(el => {
                            const match = el.style.backgroundImage.match(/url\\(['"]?(https?[^'"\\)]+)/);
                            if (match) urls.push(match[1]);
                        });
                        return [...new Set(urls)].slice(0, 100);
                    }
                    """);
            return images;
        } catch (Exception e) {
            log.warn("[Playwright] Viewer collect images failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Get the visible text content from the current viewer page.
     */
    public synchronized String viewerGetText() {
        if (viewerPage == null || viewerPage.isClosed()) return "No page loaded in browser.";
        try {
            String text = viewerPage.innerText("body");
            if (text.length() > 8000) {
                text = text.substring(0, 8000) + "\n... (truncated)";
            }
            return text;
        } catch (Exception e) {
            return "Failed to read page: " + e.getMessage();
        }
    }

    /** Called by tool methods after navigating — mirrors URL to the viewer page. */
    public void mirrorToViewer(String url) {
        if (viewerPage != null && !viewerPage.isClosed()) {
            try {
                viewerPage.navigate(url);
                viewerPage.waitForLoadState(LoadState.DOMCONTENTLOADED);
                lastUrl = viewerPage.url();
                lastTitle = viewerPage.title();
            } catch (Exception ignored) {}
        } else {
            lastUrl = url;
            lastTitle = "";
        }
    }

    private String sanitizeName(String name) {
        if (name == null || name.isBlank()) return "unnamed";
        String safe = name.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return safe.isEmpty() ? "unnamed" : (safe.length() > 60 ? safe.substring(0, 60) : safe);
    }

    @PreDestroy
    public void shutdown() {
        synchronized (lock) {
            log.info("[Playwright] Shutting down browser...");
            try { if (viewerContext != null) viewerContext.close(); } catch (Exception ignored) {}
            viewerPage = null;
            viewerContext = null;
            try { if (browser != null) browser.close(); } catch (Exception ignored) {}
            try { if (playwright != null) playwright.close(); } catch (Exception ignored) {}
            browser = null;
            playwright = null;
        }
    }
}
