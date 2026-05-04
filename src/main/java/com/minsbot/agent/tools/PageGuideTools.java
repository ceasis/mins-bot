package com.minsbot.agent.tools;

import com.microsoft.playwright.Page;
import com.minsbot.agent.ChromeCdpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Giya-class "AI guide for any website" features, but driven through the
 * existing CDP + chat + TTS pipeline instead of a Chrome extension.
 *
 * <ul>
 *   <li>{@link #explainCurrentPage} — "what is this page?" → DOM → summary → TTS</li>
 *   <li>{@link #readAloudCurrentPage} — narrate the active tab through Piper</li>
 *   <li>{@link #guidedWalkthrough} — generate numbered steps for "how do I X here?"</li>
 *   <li>{@link #extractStructuredData} — "give me a CSV of all rows on this page"</li>
 * </ul>
 *
 * Each finds the target tab by URL substring (or the first/active tab if blank).
 */
@Component
public class PageGuideTools {

    private static final Logger log = LoggerFactory.getLogger(PageGuideTools.class);

    private static final int MAX_PAGE_CHARS = 12_000; // cap fed to LLM to stay cheap

    private final ChromeCdpService cdp;
    private final ToolExecutionNotifier notifier;

    @Autowired(required = false) @Qualifier("chatClient") private ChatClient chatClient;
    @Autowired(required = false) private TtsTools ttsTools;

    public PageGuideTools(ChromeCdpService cdp, ToolExecutionNotifier notifier) {
        this.cdp = cdp;
        this.notifier = notifier;
    }

    @Tool(description = "Read what's on the user's current Chrome tab and summarize it aloud. "
            + "Use when the user says 'explain this page', 'what is this', 'summarize this tab', "
            + "'what am I looking at'. Speaks the summary through the bot's voice. Pass siteUrlContains "
            + "to target a specific tab (e.g. 'wikipedia.org'); leave empty for the active/first tab.")
    public String explainCurrentPage(
            @ToolParam(description = "Optional substring of the tab URL. Empty = active tab.", required = false)
            String siteUrlContains) {
        if (notifier != null) notifier.notify("📖 reading the page...");
        Page page = resolvePage(siteUrlContains);
        if (page == null) return "No matching Chrome tab found. Open the page in Chrome with CDP enabled.";
        try {
            String text = pageText(page);
            String prompt = "Summarize this web page in 3-4 plain-English sentences. Then list 3 "
                    + "key takeaways as bullet points. Be conversational — this will be read aloud.\n\n"
                    + "URL: " + page.url() + "\nTitle: " + safeTitle(page) + "\n\nPAGE TEXT:\n" + text;
            String reply = chat(prompt);
            if (reply == null || reply.isBlank()) return "Couldn't summarize the page.";
            speakNarration(reply);
            return "📖 " + safeTitle(page) + "\n\n" + reply;
        } catch (Exception e) {
            log.warn("[PageGuide] explainCurrentPage failed", e);
            return "Failed: " + e.getMessage();
        }
    }

    @Tool(description = "Read the entire active Chrome tab aloud through the bot's narration voice. "
            + "Use when the user says 'read this aloud', 'read this tab', 'narrate this article', "
            + "'read this to me'. Uses the slower narration speech rate so it sounds like an audiobook.")
    public String readAloudCurrentPage(
            @ToolParam(description = "Optional substring of the tab URL. Empty = active tab.", required = false)
            String siteUrlContains) {
        if (ttsTools == null) return "TTS not available.";
        if (notifier != null) notifier.notify("🔊 reading tab aloud...");
        Page page = resolvePage(siteUrlContains);
        if (page == null) return "No matching Chrome tab found.";
        try {
            String text = pageText(page);
            // Strip nav-y noise: collapse whitespace runs, drop super-short lines that
            // are likely menu chrome.
            String cleaned = stripChrome(text);
            if (cleaned.isBlank()) return "Page had no readable text.";
            ttsTools.speakNarrationAsync(cleaned);
            int chars = cleaned.length();
            return "🔊 narrating " + chars + " chars from \"" + safeTitle(page) + "\". "
                    + "Estimated " + Math.max(1, chars / 900) + " min at narration speed.";
        } catch (Exception e) {
            return "Failed: " + e.getMessage();
        }
    }

    @Tool(description = "Generate a step-by-step walkthrough for doing something on the current "
            + "Chrome tab. Use when the user says 'how do I X on this site', 'walk me through X', "
            + "'guide me through X here'. Produces numbered steps grounded in the page's actual "
            + "buttons / menus. The first step is narrated aloud; the rest are returned as text "
            + "so the user can read along.")
    public String guidedWalkthrough(
            @ToolParam(description = "What the user wants to accomplish, e.g. 'change my password', "
                    + "'export my data', 'cancel my subscription'") String task,
            @ToolParam(description = "Optional substring of the tab URL. Empty = active tab.", required = false)
            String siteUrlContains) {
        if (task == null || task.isBlank()) return "Tell me what you want to do on this page.";
        if (notifier != null) notifier.notify("🧭 building walkthrough for: " + task);
        Page page = resolvePage(siteUrlContains);
        if (page == null) return "No matching Chrome tab found.";
        try {
            String text = pageText(page);
            String prompt = "I'm on this web page and I want to: " + task + "\n\n"
                    + "Generate 3-7 NUMBERED steps to accomplish this. Each step must reference a "
                    + "specific button label, menu name, or link visible in the page text. Don't "
                    + "invent UI that isn't there. If the task isn't possible on this page, say so "
                    + "in step 1 and stop.\n\nFormat: each step on its own line as '1. ...', '2. ...', etc.\n\n"
                    + "URL: " + page.url() + "\nTitle: " + safeTitle(page) + "\n\nPAGE TEXT:\n" + text;
            String reply = chat(prompt);
            if (reply == null || reply.isBlank()) return "Couldn't generate steps.";
            // Speak step 1 only — let user read the rest while doing each step
            String firstStep = extractFirstStep(reply);
            if (firstStep != null && !firstStep.isBlank()) speakNarration("Step 1: " + firstStep);
            return "🧭 " + task + "\n\n" + reply
                    + "\n\n(Step 1 narrated. Read along for steps 2+, or ask 'next step' for narration.)";
        } catch (Exception e) {
            return "Failed: " + e.getMessage();
        }
    }

    @Tool(description = "Extract structured data (table / list / records) from the current Chrome "
            + "tab as CSV. Use when the user says 'get a CSV of all the products on this page', "
            + "'list every job posting', 'extract all the rows', 'scrape this table'. Returns CSV "
            + "text the user can paste or pipe to a file. The LLM picks reasonable column headers "
            + "from the page if you don't specify.")
    public String extractStructuredData(
            @ToolParam(description = "What rows to extract, e.g. 'all product cards', 'jobs', "
                    + "'flight options'. The LLM uses this to know what fragments to focus on.") String whatToExtract,
            @ToolParam(description = "Comma-separated columns to include, e.g. 'name,price,url'. "
                    + "Empty = LLM picks reasonable columns.", required = false) String columns,
            @ToolParam(description = "Optional substring of the tab URL. Empty = active tab.", required = false)
            String siteUrlContains) {
        if (whatToExtract == null || whatToExtract.isBlank())
            return "Tell me what to extract (e.g. 'all product names and prices').";
        if (notifier != null) notifier.notify("📊 extracting " + whatToExtract + "...");
        Page page = resolvePage(siteUrlContains);
        if (page == null) return "No matching Chrome tab found.";
        try {
            String text = pageText(page);
            String colHint = (columns == null || columns.isBlank())
                    ? "Pick reasonable columns yourself based on what's on the page."
                    : "Use exactly these columns in this order: " + columns + ".";
            String prompt = "Extract " + whatToExtract + " from this web page as CSV.\n"
                    + colHint + "\n"
                    + "Output rules:\n"
                    + "1. First line = header row.\n"
                    + "2. One row per record.\n"
                    + "3. Quote any field containing commas or quotes (RFC 4180).\n"
                    + "4. NO commentary, no markdown, no code fences — just raw CSV.\n"
                    + "5. If you can't find any matching records, output exactly the single line: NO_MATCHES\n\n"
                    + "URL: " + page.url() + "\nTitle: " + safeTitle(page) + "\n\nPAGE TEXT:\n" + text;
            String reply = chat(prompt);
            if (reply == null || reply.isBlank()) return "No CSV produced.";
            // Strip accidental code fences the LLM sometimes adds
            String csv = reply.trim();
            if (csv.startsWith("```")) {
                int firstNl = csv.indexOf('\n');
                if (firstNl >= 0) csv = csv.substring(firstNl + 1);
                if (csv.endsWith("```")) csv = csv.substring(0, csv.length() - 3).trim();
            }
            if (csv.equalsIgnoreCase("NO_MATCHES")) return "No " + whatToExtract + " found on this page.";
            int rows = (int) csv.lines().count() - 1;
            return "📊 " + Math.max(0, rows) + " rows extracted from \"" + safeTitle(page) + "\":\n\n" + csv;
        } catch (Exception e) {
            return "Failed: " + e.getMessage();
        }
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private Page resolvePage(String siteUrlContains) {
        try {
            cdp.ensureConnected();
        } catch (Exception e) {
            return null;
        }
        Page p;
        if (siteUrlContains != null && !siteUrlContains.isBlank()) {
            p = cdp.findPageByUrl(siteUrlContains);
            if (p != null) return p;
        }
        p = cdp.getActivePage(null);
        return p;
    }

    private static String pageText(Page p) {
        String text;
        try { text = p.innerText("body"); }
        catch (Exception e) { text = ""; }
        if (text == null) text = "";
        if (text.length() > MAX_PAGE_CHARS) {
            text = text.substring(0, MAX_PAGE_CHARS) + "\n... (truncated)";
        }
        return text;
    }

    private static String safeTitle(Page p) {
        try { return p.title(); } catch (Exception e) { return p.url(); }
    }

    private String chat(String prompt) {
        if (chatClient == null) return null;
        try {
            return chatClient.prompt().user(prompt).call().content();
        } catch (Exception e) {
            log.warn("[PageGuide] chat failed: {}", e.getMessage());
            return null;
        }
    }

    private void speakNarration(String text) {
        if (ttsTools == null || text == null || text.isBlank()) return;
        try { ttsTools.speakNarrationAsync(text); } catch (Exception ignored) {}
    }

    /** Strip lines that are likely nav chrome (very short, no punctuation),
     *  collapse blank-line runs to single blanks. */
    private static String stripChrome(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        boolean prevBlank = false;
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                if (!prevBlank) sb.append('\n');
                prevBlank = true;
                continue;
            }
            // Skip very short lines with no end punctuation — likely menu items
            if (line.length() < 4) continue;
            if (line.length() < 25 && !line.matches(".*[.!?:;].*")) continue;
            sb.append(line).append('\n');
            prevBlank = false;
        }
        return sb.toString().trim();
    }

    private static String extractFirstStep(String reply) {
        for (String line : reply.split("\\R")) {
            String t = line.trim();
            // Match "1. ...", "1) ...", "Step 1: ..."
            if (t.matches("^(?:\\*\\*)?(?:Step\\s*)?1[.):][ \\t].*")) {
                return t.replaceFirst("^(?:\\*\\*)?(?:Step\\s*)?1[.):][ \\t]+", "")
                        .replaceAll("\\*\\*", "")
                        .trim();
            }
        }
        return null;
    }
}
