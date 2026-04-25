package com.minsbot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One-shot research assistant. Takes a natural-language query, runs a web
 * search, fetches the top 3 pages' text, and returns a synthesized summary
 * with source citations — all in a single tool call the LLM can issue.
 *
 * <p>Replaces the fragile chain of "searchWeb → openPath(URL) → readPage → ...".
 * When the user says "check arxiv.org for app ideas" or "research the latest
 * Spring Boot 3 changes", the LLM should prefer THIS tool.</p>
 */
@Component
public class ResearchTool {

    private static final Logger log = LoggerFactory.getLogger(ResearchTool.class);
    private static final Pattern URL_IN_RESULTS = Pattern.compile("https?://\\S+");

    private final WebSearchTools webSearchTools;
    private final PlaywrightService playwright;
    private final ChatClient chatClient;
    private final ToolExecutionNotifier notifier;

    public ResearchTool(WebSearchTools webSearchTools,
                        PlaywrightService playwright,
                        ChatClient.Builder chatClientBuilder,
                        ToolExecutionNotifier notifier) {
        this.webSearchTools = webSearchTools;
        this.playwright = playwright;
        this.chatClient = chatClientBuilder.build();
        this.notifier = notifier;
    }

    @Tool(description = "Research a topic end-to-end: runs a web search, fetches the top 3 pages' text, "
            + "and returns a synthesized summary with source URLs. Use for 'check arxiv.org for X', "
            + "'research the latest Y', 'what are people saying about Z', 'find app ideas from site W'. "
            + "PREFER this over chaining searchWeb → openPath → readWebPage manually — one tool call "
            + "does the whole research workflow.")
    public String research(
            @ToolParam(description = "Natural language research query, e.g. 'trending AI papers on arxiv.org' "
                    + "or 'spring boot 3.3 new features'") String query) {
        try {
            if (query == null || query.isBlank()) return "Error: query is required.";
            notifier.notify("🔎 searching: " + query);
            String searchResults = webSearchTools.searchWeb(query);
            if (searchResults == null || searchResults.isBlank()) return "No search results.";

            // Extract the first 3 distinct URLs from the results block.
            List<String> urls = new ArrayList<>();
            Matcher m = URL_IN_RESULTS.matcher(searchResults);
            while (m.find() && urls.size() < 3) {
                String u = m.group().replaceAll("[)\\].,;]+$", "");
                if (!urls.contains(u)) urls.add(u);
            }
            if (urls.isEmpty()) {
                return "Search succeeded but no URLs found. Raw results:\n" + searchResults;
            }

            StringBuilder corpus = new StringBuilder();
            corpus.append("SEARCH RESULTS SUMMARY:\n").append(truncate(searchResults, 1500)).append("\n\n");
            for (int i = 0; i < urls.size(); i++) {
                String url = urls.get(i);
                notifier.notify("📄 fetching source " + (i + 1) + ": " + url);
                String text;
                try {
                    text = playwright.getPageText(url);
                } catch (Exception e) {
                    text = "(fetch failed: " + e.getMessage() + ")";
                }
                corpus.append("── SOURCE ").append(i + 1).append(": ").append(url).append(" ──\n")
                      .append(truncate(text, 4000)).append("\n\n");
            }

            notifier.notify("🧠 synthesizing...");
            String synthesisPrompt =
                    "Research query: " + query + "\n\n"
                    + "Below are excerpts from " + urls.size() + " web sources. Synthesize a clear, "
                    + "specific answer to the user's query. Cite sources by number [1], [2], [3]. "
                    + "If the sources contradict, note it. If the sources don't directly answer "
                    + "the query, say so explicitly — don't hallucinate.\n\n" + corpus;
            String synthesis = chatClient.prompt().user(synthesisPrompt).call().content();
            if (synthesis == null || synthesis.isBlank()) synthesis = "(synthesis returned empty)";

            StringBuilder out = new StringBuilder();
            out.append(synthesis).append("\n\n── sources ──\n");
            for (int i = 0; i < urls.size(); i++) {
                out.append("[").append(i + 1).append("] ").append(urls.get(i)).append("\n");
            }
            return out.toString();
        } catch (Exception e) {
            log.warn("[Research] failed: {}", e.getMessage(), e);
            return "Research error: " + e.getMessage();
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
