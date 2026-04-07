package com.minsbot.agent.tools;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.LoadState;
import com.minsbot.agent.ChromeCdpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    @Tool(description = "Execute JavaScript code directly in a Chrome tab via CDP. "
            + "The fastest way to fill forms, read DOM values, click buttons, or manipulate page state. "
            + "Returns the result of the JS expression (stringified). "
            + "PREFERRED for filling entire forms in one call — example: "
            + "browserExecuteJs('sentientdev.com', "
            + "'document.querySelector(\"input[placeholder=\\\"Last Name\\\"]\").value=\"Smith\"; "
            + "document.querySelector(\"input[placeholder=\\\"Last Name\\\"]\").dispatchEvent(new Event(\"input\",{bubbles:true})); "
            + "\"done\"') "
            + "Use dispatchEvent(new Event('input',{bubbles:true})) after setting .value so React/frameworks detect the change. "
            + "For timed verification fields, this is INSTANT — no click/paste delay.")
    public String browserExecuteJs(
            @ToolParam(description = "Part of the site URL to find the tab, e.g. 'sentientdev.com'")
            String siteUrlContains,
            @ToolParam(description = "JavaScript code to execute in the page context. "
                    + "Use document.querySelector/querySelectorAll to find elements. "
                    + "Return a string result for confirmation.")
            String jsCode) {
        notifier.notify("CDP JS: " + (jsCode.length() > 60 ? jsCode.substring(0, 60) + "..." : jsCode));
        try {
            cdpService.ensureConnected();
            Page page = cdpService.findPageByUrl(siteUrlContains);
            if (page == null) return "FAILED: No tab found containing '" + siteUrlContains + "' in URL.";
            cdpService.activatePage(page);

            Object result = page.evaluate(jsCode);
            String resultStr = result != null ? result.toString() : "null";
            if (resultStr.length() > 2000) resultStr = resultStr.substring(0, 2000) + "...(truncated)";
            return "JS executed. Result: " + resultStr;
        } catch (Exception e) {
            return "FAILED: browserExecuteJs on " + siteUrlContains + ": " + e.getMessage();
        }
    }

    @Tool(description = "Fill an entire form at once using JavaScript injection via CDP. "
            + "Pass a pipe-separated list of 'cssSelector=value' pairs. "
            + "This is the FASTEST form-filling method — fills all fields in one ~10ms call. "
            + "Each field gets its value set AND an 'input' event dispatched (so React/Vue/Angular detect it). "
            + "Example: browserFillForm('sentientdev.com', "
            + "'input[placeholder=\"Last Name\"]=Smith|input[placeholder=\"Phone Number\"]=555-4321|input[placeholder=\"First Name\"]=Carlos')")
    public String browserFillForm(
            @ToolParam(description = "Part of the site URL to find the tab")
            String siteUrlContains,
            @ToolParam(description = "Pipe-separated 'cssSelector=value' pairs, e.g. "
                    + "'#name=John|#email=john@x.com|textarea.bio=Hello world'")
            String fieldPairs) {
        notifier.notify("CDP fill form on " + siteUrlContains);
        try {
            cdpService.ensureConnected();
            Page page = cdpService.findPageByUrl(siteUrlContains);
            if (page == null) return "FAILED: No tab found containing '" + siteUrlContains + "' in URL.";
            cdpService.activatePage(page);

            String[] pairs = fieldPairs.split("\\|");
            StringBuilder js = new StringBuilder("(function(){var r=[];");
            for (String pair : pairs) {
                int eq = pair.indexOf('=');
                if (eq < 0) continue;
                String selector = pair.substring(0, eq).trim();
                String value = pair.substring(eq + 1).trim();
                // Escape for JS strings
                String jsSel = selector.replace("\\", "\\\\").replace("'", "\\'");
                String jsVal = value.replace("\\", "\\\\").replace("'", "\\'");
                js.append("var e=document.querySelector('").append(jsSel).append("');");
                js.append("if(e){");
                js.append("var nativeSetter=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value')||Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value');");
                js.append("if(nativeSetter&&nativeSetter.set){nativeSetter.set.call(e,'").append(jsVal).append("');}else{e.value='").append(jsVal).append("';}");
                js.append("e.dispatchEvent(new Event('input',{bubbles:true}));");
                js.append("e.dispatchEvent(new Event('change',{bubbles:true}));");
                js.append("r.push('").append(jsSel).append("=OK');");
                js.append("}else{r.push('").append(jsSel).append("=NOT FOUND');}");
            }
            js.append("return r.join(', ');})()");

            Object result = page.evaluate(js.toString());
            return "Form filled: " + (result != null ? result.toString() : "done");
        } catch (Exception e) {
            return "FAILED: browserFillForm: " + e.getMessage();
        }
    }

    @Tool(description = "Click a button or element by CSS selector via JavaScript. "
            + "Faster than visual click — no screenshot needed. "
            + "Example: browserClickElement('sentientdev.com', 'button.submit-bot')")
    public String browserClickElement(
            @ToolParam(description = "Part of the site URL to find the tab")
            String siteUrlContains,
            @ToolParam(description = "CSS selector of the element to click, e.g. 'button.submit', '#login-btn', 'a[href=\"/next\"]'")
            String cssSelector) {
        notifier.notify("CDP click: " + cssSelector);
        try {
            cdpService.ensureConnected();
            Page page = cdpService.findPageByUrl(siteUrlContains);
            if (page == null) return "FAILED: No tab found containing '" + siteUrlContains + "' in URL.";
            cdpService.activatePage(page);

            String js = "(function(){var e=document.querySelector('" + cssSelector.replace("'", "\\'")
                    + "');if(e){e.click();return 'clicked';}return 'not found';})()";
            Object result = page.evaluate(js);
            return "Click result: " + (result != null ? result.toString() : "done");
        } catch (Exception e) {
            return "FAILED: browserClickElement: " + e.getMessage();
        }
    }

    @Tool(description = "Read the current value of form fields via CDP JavaScript. "
            + "Pass a pipe-separated list of CSS selectors. Returns their current values. "
            + "Use to verify form was filled correctly before submitting.")
    public String browserReadFields(
            @ToolParam(description = "Part of the site URL")
            String siteUrlContains,
            @ToolParam(description = "Pipe-separated CSS selectors, e.g. '#name|#email|textarea.bio'")
            String selectors) {
        notifier.notify("CDP read fields...");
        try {
            cdpService.ensureConnected();
            Page page = cdpService.findPageByUrl(siteUrlContains);
            if (page == null) return "FAILED: No tab found.";

            String[] sels = selectors.split("\\|");
            StringBuilder js = new StringBuilder("(function(){var r=[];");
            for (String sel : sels) {
                String s = sel.trim().replace("'", "\\'");
                js.append("var e=document.querySelector('").append(s).append("');");
                js.append("r.push('").append(s).append("=' + (e?e.value:'NOT FOUND'));");
            }
            js.append("return r.join('\\n');})()");

            Object result = page.evaluate(js.toString());
            return result != null ? result.toString() : "no result";
        } catch (Exception e) {
            return "FAILED: " + e.getMessage();
        }
    }

    // ═══ Smart click — multi-strategy Playwright element clicking ═══

    private static final Logger log = LoggerFactory.getLogger(ChromeCdpTools.class);

    /** Words to strip from element descriptions to get the core search text. */
    private static final Pattern STRIP_PATTERN = Pattern.compile(
            "^\\s*(?:the|a|an|click(?:\\s+on)?|press|tap|hit|find|locate)\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUFFIX_PATTERN = Pattern.compile(
            "\\s+(?:button|link|tab|icon|menu\\s*item|option|checkbox|radio|input|field|element)\\s*$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Smart click — tries multiple Playwright selector strategies to click an element
     * described in natural language. Called internally from SystemTools (not a @Tool).
     *
     * @param page               the active Chrome page
     * @param elementDescription natural language like "the Submit button", "Sign in link"
     * @return "OK: ..." on success, "FAIL: ..." on failure
     */
    public String smartClick(Page page, String elementDescription) {
        if (page == null || page.isClosed()) return "FAIL: page is null or closed";

        String text = extractClickText(elementDescription);
        log.info("[CDP-SmartClick] Description='{}', extracted text='{}'", elementDescription, text);

        // Strategy 1: Role-based — button
        try {
            Locator btn = page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName(text).setExact(false));
            if (btn.count() > 0 && btn.first().isVisible()) {
                btn.first().click();
                log.info("[CDP-SmartClick] BUTTON role match: '{}'", text);
                return "OK: Clicked button '" + text + "' via Playwright CDP (role=button)";
            }
        } catch (Exception e) { log.debug("[CDP-SmartClick] Button role failed: {}", e.getMessage()); }

        // Strategy 2: Role-based — link
        try {
            Locator link = page.getByRole(AriaRole.LINK,
                    new Page.GetByRoleOptions().setName(text).setExact(false));
            if (link.count() > 0 && link.first().isVisible()) {
                link.first().click();
                log.info("[CDP-SmartClick] LINK role match: '{}'", text);
                return "OK: Clicked link '" + text + "' via Playwright CDP (role=link)";
            }
        } catch (Exception e) { log.debug("[CDP-SmartClick] Link role failed: {}", e.getMessage()); }

        // Strategy 3: Role-based — menu item
        try {
            Locator menu = page.getByRole(AriaRole.MENUITEM,
                    new Page.GetByRoleOptions().setName(text).setExact(false));
            if (menu.count() > 0 && menu.first().isVisible()) {
                menu.first().click();
                log.info("[CDP-SmartClick] MENUITEM role match: '{}'", text);
                return "OK: Clicked menu item '" + text + "' via Playwright CDP (role=menuitem)";
            }
        } catch (Exception e) { log.debug("[CDP-SmartClick] Menuitem role failed: {}", e.getMessage()); }

        // Strategy 4: Role-based — tab
        try {
            Locator tab = page.getByRole(AriaRole.TAB,
                    new Page.GetByRoleOptions().setName(text).setExact(false));
            if (tab.count() > 0 && tab.first().isVisible()) {
                tab.first().click();
                log.info("[CDP-SmartClick] TAB role match: '{}'", text);
                return "OK: Clicked tab '" + text + "' via Playwright CDP (role=tab)";
            }
        } catch (Exception e) { log.debug("[CDP-SmartClick] Tab role failed: {}", e.getMessage()); }

        // Strategy 5: Visible text match (any element)
        try {
            Locator byText = page.getByText(text, new Page.GetByTextOptions().setExact(false)).first();
            if (byText.count() > 0 && byText.isVisible()) {
                byText.click();
                log.info("[CDP-SmartClick] TEXT match: '{}'", text);
                return "OK: Clicked text '" + text + "' via Playwright CDP (getByText)";
            }
        } catch (Exception e) { log.debug("[CDP-SmartClick] Text match failed: {}", e.getMessage()); }

        // Strategy 6: Label match (for form elements)
        try {
            Locator byLabel = page.getByLabel(text, new Page.GetByLabelOptions().setExact(false));
            if (byLabel.count() > 0 && byLabel.first().isVisible()) {
                byLabel.first().click();
                log.info("[CDP-SmartClick] LABEL match: '{}'", text);
                return "OK: Clicked labeled element '" + text + "' via Playwright CDP (getByLabel)";
            }
        } catch (Exception e) { log.debug("[CDP-SmartClick] Label match failed: {}", e.getMessage()); }

        // Strategy 7: Placeholder match (for inputs)
        try {
            Locator byPlaceholder = page.getByPlaceholder(text, new Page.GetByPlaceholderOptions().setExact(false));
            if (byPlaceholder.count() > 0 && byPlaceholder.first().isVisible()) {
                byPlaceholder.first().click();
                log.info("[CDP-SmartClick] PLACEHOLDER match: '{}'", text);
                return "OK: Clicked placeholder '" + text + "' via Playwright CDP (getByPlaceholder)";
            }
        } catch (Exception e) { log.debug("[CDP-SmartClick] Placeholder match failed: {}", e.getMessage()); }

        // Strategy 8: Title attribute
        try {
            Locator byTitle = page.locator("[title*='" + cssEscape(text) + "' i]").first();
            if (byTitle.count() > 0 && byTitle.isVisible()) {
                byTitle.click();
                log.info("[CDP-SmartClick] TITLE attr match: '{}'", text);
                return "OK: Clicked element with title '" + text + "' via Playwright CDP (title attr)";
            }
        } catch (Exception e) { log.debug("[CDP-SmartClick] Title attr failed: {}", e.getMessage()); }

        // Strategy 9: Aria-label attribute
        try {
            Locator byAria = page.locator("[aria-label*='" + cssEscape(text) + "' i]").first();
            if (byAria.count() > 0 && byAria.isVisible()) {
                byAria.click();
                log.info("[CDP-SmartClick] ARIA-LABEL match: '{}'", text);
                return "OK: Clicked element with aria-label '" + text + "' via Playwright CDP (aria-label)";
            }
        } catch (Exception e) { log.debug("[CDP-SmartClick] Aria-label failed: {}", e.getMessage()); }

        log.info("[CDP-SmartClick] All strategies failed for '{}'", elementDescription);
        return "FAIL: Could not find '" + elementDescription + "' via Playwright CDP";
    }

    /**
     * Extract the core clickable text from a natural language description.
     * "the Submit button" → "Submit", "click on Sign in link" → "Sign in"
     */
    static String extractClickText(String description) {
        if (description == null || description.isBlank()) return "";
        String text = description.trim();
        // Strip leading action words: "click on the", "press the", etc.
        Matcher m = STRIP_PATTERN.matcher(text);
        if (m.find()) text = text.substring(m.end());
        // Strip trailing element type: "button", "link", "tab", etc.
        Matcher s = SUFFIX_PATTERN.matcher(text);
        if (s.find()) text = text.substring(0, s.start());
        return text.trim();
    }

    /** Escape single quotes for CSS attribute selectors. */
    private static String cssEscape(String s) {
        return s.replace("'", "\\'");
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
