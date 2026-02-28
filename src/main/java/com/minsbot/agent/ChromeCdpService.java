package com.minsbot.agent;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import com.minsbot.agent.ChromeCdpConfig.ChromeCdpProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Manages a Chrome DevTools Protocol connection to the user's real Chrome browser.
 * Separate from PlaywrightService (which manages a headless Chromium for scraping).
 * On first use, connects to Chrome on the configured port. If Chrome wasn't launched
 * with CDP enabled, gracefully restarts it with --remote-debugging-port.
 */
@Service
public class ChromeCdpService {

    private static final Logger log = LoggerFactory.getLogger(ChromeCdpService.class);

    private final ChromeCdpProperties props;
    private Playwright playwright;
    private Browser browser;
    private final Object lock = new Object();
    private volatile boolean launchAttempted = false;

    public ChromeCdpService(ChromeCdpProperties props) {
        this.props = props;
    }

    /**
     * Ensure we have an active CDP connection.
     * 1. If already connected, return immediately.
     * 2. Try connecting to localhost:port.
     * 3. If that fails, close Chrome gracefully, relaunch with CDP flag, reconnect.
     */
    public void ensureConnected() {
        if (!props.isEnabled()) {
            throw new IllegalStateException("CDP is disabled (app.cdp.enabled=false)");
        }
        synchronized (lock) {
            if (browser != null && browser.isConnected()) return;

            if (playwright == null) {
                playwright = Playwright.create();
            }

            // Attempt 1: connect to existing Chrome with CDP
            if (tryConnect()) {
                log.info("[CDP] Connected to existing Chrome on port {}", props.getPort());
                return;
            }

            // Attempt 2: relaunch Chrome with CDP flag (only once per session)
            if (!launchAttempted) {
                launchAttempted = true;
                relaunchChromeWithCdp();
                if (browser != null && browser.isConnected()) {
                    log.info("[CDP] Connected to relaunched Chrome on port {}", props.getPort());
                    return;
                }
            }

            throw new RuntimeException("Failed to connect to Chrome via CDP on port " + props.getPort());
        }
    }

    /** Returns true if the CDP browser is connected and alive. */
    public boolean isConnected() {
        return browser != null && browser.isConnected();
    }

    /**
     * List all open pages (tabs) in the connected browser.
     * Returns empty list if not connected.
     */
    public List<Page> listPages() {
        if (browser == null || !browser.isConnected()) return List.of();
        List<Page> allPages = new ArrayList<>();
        for (BrowserContext ctx : browser.contexts()) {
            allPages.addAll(ctx.pages());
        }
        return allPages;
    }

    /**
     * Find the first page whose URL contains the given pattern (case-insensitive).
     * Returns null if no match found.
     */
    public Page findPageByUrl(String urlContains) {
        if (urlContains == null || urlContains.isBlank()) return null;
        String lower = urlContains.toLowerCase();
        for (Page page : listPages()) {
            try {
                if (page.url().toLowerCase().contains(lower)) {
                    return page;
                }
            } catch (Exception ignored) {} // page may be closed
        }
        return null;
    }

    /** Bring a page/tab to the front. */
    public void activatePage(Page page) {
        if (page != null && !page.isClosed()) {
            page.bringToFront();
        }
    }

    /**
     * Open a new tab and navigate to the given URL.
     * Returns the new Page.
     */
    public Page openPage(String url) {
        ensureConnected();
        List<BrowserContext> contexts = browser.contexts();
        if (contexts.isEmpty()) {
            throw new RuntimeException("No browser contexts available");
        }
        Page page = contexts.get(0).newPage();
        page.navigate(url);
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        return page;
    }

    /** Try to connect to a running Chrome's CDP endpoint. */
    private boolean tryConnect() {
        try {
            String endpoint = "http://localhost:" + props.getPort();
            browser = playwright.chromium().connectOverCDP(endpoint);
            return browser.isConnected();
        } catch (Exception e) {
            log.debug("[CDP] Could not connect to port {}: {}", props.getPort(), e.getMessage());
            browser = null;
            return false;
        }
    }

    /** Close Chrome gracefully, relaunch with --remote-debugging-port, reconnect. */
    private void relaunchChromeWithCdp() {
        String chromePath = detectChromePath();
        if (chromePath == null) {
            log.error("[CDP] Chrome not found. Install Chrome or set app.cdp.chrome-path.");
            return;
        }

        log.info("[CDP] Closing Chrome gracefully and relaunching with CDP on port {}...", props.getPort());

        // Graceful close (NOT /F) — allows Chrome to save session
        try {
            Process kill = new ProcessBuilder("taskkill", "/IM", "chrome.exe")
                    .redirectErrorStream(true).start();
            kill.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("[CDP] taskkill failed (Chrome may not be running): {}", e.getMessage());
        }

        // Wait for Chrome to close
        try { Thread.sleep(2000); } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        // Relaunch with CDP flag + restore session
        try {
            new ProcessBuilder(
                    chromePath,
                    "--remote-debugging-port=" + props.getPort(),
                    "--restore-last-session"
            ).start();
            log.info("[CDP] Chrome launched with --remote-debugging-port={}", props.getPort());
        } catch (Exception e) {
            log.error("[CDP] Failed to launch Chrome: {}", e.getMessage());
            return;
        }

        // Poll for CDP port to become available (max 10 seconds)
        for (int i = 0; i < 20; i++) {
            try { Thread.sleep(500); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
            if (tryConnect()) return;
        }

        log.error("[CDP] Chrome launched but CDP connection failed after 10s");
    }

    /** Detect Chrome executable path on Windows. */
    private String detectChromePath() {
        // 1. User-configured path
        if (props.getChromePath() != null && !props.getChromePath().isBlank()) {
            return props.getChromePath();
        }

        // 2. Common Windows Chrome installation paths
        String[] candidates = {
                System.getenv("LOCALAPPDATA") + "\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe"
        };
        for (String path : candidates) {
            if (path != null && Files.exists(Path.of(path))) {
                log.info("[CDP] Found Chrome at: {}", path);
                return path;
            }
        }

        // 3. Try 'where chrome' command
        try {
            Process p = new ProcessBuilder("where", "chrome").redirectErrorStream(true).start();
            String output;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                output = r.lines().collect(Collectors.joining("\n"));
            }
            p.waitFor();
            if (p.exitValue() == 0 && !output.isBlank()) {
                String first = output.lines().findFirst().orElse("").trim();
                if (!first.isEmpty() && Files.exists(Path.of(first))) {
                    log.info("[CDP] Found Chrome via 'where': {}", first);
                    return first;
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    @PreDestroy
    public void close() {
        synchronized (lock) {
            log.info("[CDP] Shutting down CDP connection...");
            try { if (browser != null) browser.close(); } catch (Exception ignored) {}
            try { if (playwright != null) playwright.close(); } catch (Exception ignored) {}
            browser = null;
            playwright = null;
        }
    }
}
