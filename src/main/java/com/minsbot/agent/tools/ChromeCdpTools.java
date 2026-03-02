package com.minsbot.agent.tools;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.minsbot.agent.ChromeCdpService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Browser automation tools via Chrome DevTools Protocol (CDP).
 * Connects to the user's real Chrome browser and performs DOM-level actions —
 * no screen coordinates, no OCR, no focus issues.
 */
@Component
public class ChromeCdpTools {

    private final ChromeCdpService cdpService;
    private final ToolExecutionNotifier notifier;

    /** Smart CSS selector cascade for finding search inputs across popular sites. */
    private static final String SEARCH_SELECTOR_CASCADE =
            "textarea[name='q'], input[name='q'], " +
            "input[name='search_query'], " +
            "input[type='search'], input[role='searchbox'], " +
            "input[aria-label*='Search' i], input[placeholder*='Search' i], " +
            "textarea[aria-label*='Search' i], " +
            "input[type='text']:first-of-type";

    public ChromeCdpTools(ChromeCdpService cdpService, ToolExecutionNotifier notifier) {
        this.cdpService = cdpService;
        this.notifier = notifier;
    }

    @Tool(description = "Search on a website by typing into its search box and pressing Enter. "
            + "Uses Chrome CDP (DOM-level, no screen coordinates needed). "
            + "Finds the Chrome tab matching the site URL, locates the search input, types the query, presses Enter. "
            + "If the site is not open, opens it first. Works for Google, YouTube, Amazon, and most sites. "
            + "Example: browserSearch('google.com', 'bose speakers') — searches Google. "
            + "Example: browserSearch('youtube.com', 'music') — searches YouTube.")
    public String browserSearch(
            @ToolParam(description = "Part of the site URL to find the tab, e.g. 'google.com', 'youtube.com', 'amazon.com'")
            String siteUrlContains,
            @ToolParam(description = "The search query to type and submit")
            String searchQuery) {
        notifier.notify("CDP search on " + siteUrlContains + ": " + searchQuery);
        try {
            cdpService.ensureConnected();

            // Find existing tab or open the site
            Page page = cdpService.findPageByUrl(siteUrlContains);
            if (page == null) {
                String url = siteUrlContains.startsWith("http")
                        ? siteUrlContains
                        : "https://" + (siteUrlContains.contains(".") ? siteUrlContains : siteUrlContains + ".com");
                page = cdpService.openPage(url);
            }
            cdpService.activatePage(page);

            // Find and fill the search input
            Locator searchInput = page.locator(SEARCH_SELECTOR_CASCADE).first();
            searchInput.waitFor(new Locator.WaitForOptions().setTimeout(5000));
            searchInput.click();
            searchInput.fill(searchQuery);
            searchInput.press("Enter");

            // Wait for navigation/results
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            try { page.waitForLoadState(LoadState.NETWORKIDLE); } catch (Exception ignored) {}

            String title = page.title();
            String currentUrl = page.url();
            return "Searched '" + searchQuery + "' on " + siteUrlContains
                    + ". Page title: " + title + " | URL: " + currentUrl;
        } catch (Exception e) {
            return "FAILED: browserSearch on " + siteUrlContains + ": " + e.getMessage()
                    + ". Fallback: use typeInBrowserInput instead.";
        }
    }

    @Tool(description = "Click a button or link by its visible text on a website using Chrome CDP (DOM-level). "
            + "No screen coordinates needed. Finds the Chrome tab by URL pattern, then clicks the element. "
            + "Example: browserClickButton('youtube.com', 'Sign in')")
    public String browserClickButton(
            @ToolParam(description = "Part of the site URL to find the tab, e.g. 'youtube.com'")
            String siteUrlContains,
            @ToolParam(description = "The visible text of the button or link to click")
            String buttonText) {
        notifier.notify("CDP click '" + buttonText + "' on " + siteUrlContains);
        try {
            cdpService.ensureConnected();
            Page page = cdpService.findPageByUrl(siteUrlContains);
            if (page == null) return "FAILED: No tab found containing '" + siteUrlContains + "' in URL.";
            cdpService.activatePage(page);

            page.getByText(buttonText, new Page.GetByTextOptions().setExact(false)).first().click();
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);

            return "Clicked '" + buttonText + "'. Page title: " + page.title();
        } catch (Exception e) {
            return "FAILED: browserClickButton '" + buttonText + "' on " + siteUrlContains + ": " + e.getMessage();
        }
    }

    @Tool(description = "Fill a form field on a website using Chrome CDP (DOM-level). "
            + "Specify the field by CSS selector. No screen coordinates needed. "
            + "Example: browserFillField('login.com', 'input[name=email]', 'me@example.com')")
    public String browserFillField(
            @ToolParam(description = "Part of the site URL to find the tab")
            String siteUrlContains,
            @ToolParam(description = "CSS selector of the input field, e.g. 'input[name=email]', '#username', 'textarea.comment'")
            String cssSelector,
            @ToolParam(description = "The value to fill into the field")
            String value) {
        notifier.notify("CDP fill '" + cssSelector + "' on " + siteUrlContains);
        try {
            cdpService.ensureConnected();
            Page page = cdpService.findPageByUrl(siteUrlContains);
            if (page == null) return "FAILED: No tab found containing '" + siteUrlContains + "' in URL.";
            cdpService.activatePage(page);

            page.fill(cssSelector, value);
            return "Filled '" + cssSelector + "' with '" + value + "' on " + siteUrlContains + ".";
        } catch (Exception e) {
            return "FAILED: browserFillField '" + cssSelector + "' on " + siteUrlContains + ": " + e.getMessage();
        }
    }

    @Tool(description = "List all open Chrome tabs with their URLs and titles via CDP. "
            + "More reliable than PowerShell-based tab listing.")
    public String browserListOpenTabs() {
        notifier.notify("Listing Chrome tabs via CDP...");
        try {
            cdpService.ensureConnected();
            List<Page> pages = cdpService.listPages();
            if (pages.isEmpty()) return "No tabs found.";

            StringBuilder sb = new StringBuilder("Open tabs (" + pages.size() + "):\n");
            for (int i = 0; i < pages.size(); i++) {
                Page p = pages.get(i);
                try {
                    sb.append(i + 1).append(". ").append(p.title())
                            .append(" | ").append(p.url()).append("\n");
                } catch (Exception e) {
                    sb.append(i + 1).append(". (error reading tab)\n");
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "FAILED: browserListOpenTabs: " + e.getMessage();
        }
    }

    @Tool(description = "Extract the visible text content from a Chrome tab via CDP. "
            + "Finds the tab by URL pattern. Returns up to 5000 characters. "
            + "Use to read web page content without screenshots.")
    public String browserGetPageText(
            @ToolParam(description = "Part of the URL to find the tab, e.g. 'google.com', 'wikipedia.org'")
            String siteUrlContains) {
        notifier.notify("Reading page text from " + siteUrlContains + "...");
        try {
            cdpService.ensureConnected();
            Page page = cdpService.findPageByUrl(siteUrlContains);
            if (page == null) return "FAILED: No tab found containing '" + siteUrlContains + "' in URL.";

            String text = page.innerText("body");
            if (text.length() > 5000) {
                text = text.substring(0, 5000) + "\n... (truncated at 5000 chars)";
            }
            return "Page: " + page.title() + " | URL: " + page.url() + "\n\n" + text;
        } catch (Exception e) {
            return "FAILED: browserGetPageText for '" + siteUrlContains + "': " + e.getMessage();
        }
    }

    @Tool(description = "Navigate an existing Chrome tab to a new URL via CDP. "
            + "Finds the tab by URL pattern, then navigates it. If the tab has content loaded "
            + "(not a blank/new tab), opens a NEW tab instead to avoid losing the user's page.")
    public String browserNavigateCdp(
            @ToolParam(description = "Part of the current URL to find the tab, e.g. 'google.com'")
            String siteUrlContains,
            @ToolParam(description = "The new URL to navigate to")
            String newUrl) {
        notifier.notify("CDP navigate " + siteUrlContains + " → " + newUrl);
        try {
            cdpService.ensureConnected();
            Page page = cdpService.findPageByUrl(siteUrlContains);
            if (page == null) return "FAILED: No tab found containing '" + siteUrlContains + "' in URL.";

            // Check if tab has real content — if so, open new tab instead
            String currentUrl = page.url();
            if (hasContent(currentUrl)) {
                page = cdpService.openPage(newUrl);
                cdpService.activatePage(page);
                return "Opened NEW tab (existing tab had content): " + page.url() + " | Title: " + page.title();
            }

            cdpService.activatePage(page);
            page.navigate(newUrl);
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);

            return "Navigated to: " + page.url() + " | Title: " + page.title();
        } catch (Exception e) {
            return "FAILED: browserNavigateCdp: " + e.getMessage();
        }
    }

    @Tool(description = "Open a URL in a NEW Chrome tab via CDP. Never overwrites existing tabs. "
            + "Use this to open booking links, search results, or any URL the user wants to see.")
    public String browserOpenNewTab(
            @ToolParam(description = "The URL to open in a new tab") String url) {
        notifier.notify("Opening new tab: " + url);
        try {
            cdpService.ensureConnected();
            Page page = cdpService.openPage(url);
            cdpService.activatePage(page);
            return "Opened new tab: " + page.url() + " | Title: " + page.title();
        } catch (Exception e) {
            return "FAILED: browserOpenNewTab: " + e.getMessage();
        }
    }

    /** Check if a URL represents a page with real content (not blank/new tab). */
    private boolean hasContent(String url) {
        if (url == null || url.isBlank()) return false;
        String lower = url.toLowerCase();
        // These are "empty" tab URLs
        return !lower.equals("about:blank")
                && !lower.equals("chrome://newtab/")
                && !lower.equals("chrome://new-tab-page/")
                && !lower.startsWith("chrome://newtab")
                && !lower.equals("edge://newtab/")
                && !lower.equals("");
    }
}
