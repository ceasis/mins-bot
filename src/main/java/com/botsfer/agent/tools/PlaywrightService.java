package com.botsfer.agent.tools;

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

    /** Get (or lazily create) the shared browser. Auto-installs Chromium on first use. */
    private Browser getBrowser() {
        synchronized (lock) {
            if (browser == null || !browser.isConnected()) {
                // Try to launch — if Chromium isn't installed, install it and retry
                try {
                    playwright = Playwright.create();
                    browser = launchChromium(playwright);
                } catch (Exception e) {
                    if (!installAttempted) {
                        installAttempted = true;
                        log.info("[Playwright] Chromium not found — installing automatically...");
                        installChromium();
                        // Retry after install
                        if (playwright != null) {
                            try { playwright.close(); } catch (Exception ignored) {}
                        }
                        playwright = Playwright.create();
                        browser = launchChromium(playwright);
                    } else {
                        throw e;
                    }
                }
                log.info("[Playwright] Browser ready.");
            }
            return browser;
        }
    }

    /** Launch headless Chromium with standard options. */
    private Browser launchChromium(Playwright pw) {
        return pw.chromium().launch(
                new BrowserType.LaunchOptions()
                        .setHeadless(true)
                        .setArgs(List.of(
                                "--disable-gpu",
                                "--no-sandbox",
                                "--disable-dev-shm-usage"
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

    /** Create a fresh browser context + page with sensible defaults. */
    private Page newPage() {
        BrowserContext context = getBrowser().newContext(
                new Browser.NewContextOptions()
                        .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .setViewportSize(1920, 1080)
                        .setLocale("en-US")
        );
        context.setDefaultTimeout(30000);
        return context.newPage();
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

    /** Get or create the persistent viewer page. */
    public synchronized Page getViewerPage() {
        if (viewerPage == null || viewerPage.isClosed()) {
            viewerContext = getBrowser().newContext(
                    new Browser.NewContextOptions()
                            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                            .setViewportSize(1280, 720)
                            .setLocale("en-US")
            );
            viewerContext.setDefaultTimeout(30000);
            viewerPage = viewerContext.newPage();
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
