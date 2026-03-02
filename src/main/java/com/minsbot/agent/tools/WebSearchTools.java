package com.minsbot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text-based web search tools. Returns readable search results (titles, snippets, links)
 * for research, fact-finding, price lookups, flight/hotel searches, etc.
 * Uses DuckDuckGo HTML search (no API key required).
 */
@Component
public class WebSearchTools {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTools.class);

    private final ToolExecutionNotifier notifier;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public WebSearchTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @Tool(description = "Search the web and return text results (titles, snippets, links). " +
            "Use this for ANY research task: looking up facts, prices, flights, hotels, products, " +
            "news, reviews, recipes, how-to guides, etc. Returns readable text, NOT images. " +
            "For image downloads use searchAndDownloadImages instead.")
    public String searchWeb(
            @ToolParam(description = "The search query, e.g. 'flights from Manila to Taiwan April 2026'") String query) {
        notifier.notify("Searching: " + query);
        try {
            // Try DuckDuckGo HTML search first
            String results = searchDuckDuckGo(query);
            if (results != null && !results.isBlank()) {
                log.info("[WebSearch] DDG returned results for: {}", query);
                return results;
            }

            // Fallback: try Google search scraping
            results = searchGoogleScrape(query);
            if (results != null && !results.isBlank()) {
                log.info("[WebSearch] Google scrape returned results for: {}", query);
                return results;
            }

            return "No results found for: " + query;
        } catch (Exception e) {
            log.warn("[WebSearch] Failed for '{}': {}", query, e.getMessage());
            return "Search failed: " + e.getMessage();
        }
    }

    @Tool(description = "Fetch a specific web page and return its readable text content. " +
            "Use this after searchWeb to read the full content of a specific result page.")
    public String readWebPage(
            @ToolParam(description = "The full URL to read, e.g. 'https://example.com/article'") String url) {
        notifier.notify("Reading: " + url);
        try {
            String html = fetchHtml(url);
            if (html == null || html.isBlank()) return "Could not fetch page.";

            // Strip scripts, styles, then HTML tags
            String text = html.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
            text = text.replaceAll("(?is)<style[^>]*>.*?</style>", " ");
            text = text.replaceAll("(?is)<nav[^>]*>.*?</nav>", " ");
            text = text.replaceAll("(?is)<footer[^>]*>.*?</footer>", " ");
            text = text.replaceAll("(?is)<header[^>]*>.*?</header>", " ");
            text = text.replaceAll("<[^>]+>", " ");
            text = text.replaceAll("&nbsp;", " ").replaceAll("&amp;", "&")
                    .replaceAll("&lt;", "<").replaceAll("&gt;", ">")
                    .replaceAll("&quot;", "\"").replaceAll("&#39;", "'");
            text = text.replaceAll("\\s{3,}", "\n").trim();

            if (text.length() > 8000) {
                text = text.substring(0, 8000) + "\n... (truncated)";
            }
            return text.isBlank() ? "Page loaded but no readable text found." : text;
        } catch (Exception e) {
            return "Failed to read page: " + e.getMessage();
        }
    }

    // ═══ DuckDuckGo HTML search ═══

    private String searchDuckDuckGo(String query) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://html.duckduckgo.com/html/?q=" + encoded;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[WebSearch] DDG returned status {}", response.statusCode());
                return null;
            }

            String html = response.body();
            return parseDdgResults(html, query);
        } catch (Exception e) {
            log.warn("[WebSearch] DDG search failed: {}", e.getMessage());
            return null;
        }
    }

    /** Parse DuckDuckGo HTML search results page. */
    private String parseDdgResults(String html, String query) {
        List<SearchResult> results = new ArrayList<>();

        // DDG result blocks: <div class="result ..."> containing <a class="result__a"> and <a class="result__snippet">
        Pattern resultBlock = Pattern.compile(
                "<a[^>]+class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>.*?" +
                        "class=\"result__snippet\"[^>]*>(.*?)</",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

        Matcher m = resultBlock.matcher(html);
        while (m.find() && results.size() < 10) {
            String link = m.group(1).trim();
            String title = stripTags(m.group(2)).trim();
            String snippet = stripTags(m.group(3)).trim();

            // DDG wraps links through a redirect — extract the actual URL
            if (link.contains("uddg=")) {
                try {
                    String decoded = java.net.URLDecoder.decode(
                            link.substring(link.indexOf("uddg=") + 5), StandardCharsets.UTF_8);
                    if (decoded.contains("&")) decoded = decoded.substring(0, decoded.indexOf("&"));
                    link = decoded;
                } catch (Exception ignored) {}
            }

            if (!title.isBlank() && !link.isBlank()) {
                results.add(new SearchResult(title, snippet, link));
            }
        }

        if (results.isEmpty()) return null;

        return formatResults(results, query);
    }

    // ═══ Google search fallback (scraping) ═══

    private String searchGoogleScrape(String query) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://www.google.com/search?q=" + encoded + "&hl=en&num=10";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;

            return parseGoogleResults(response.body(), query);
        } catch (Exception e) {
            log.warn("[WebSearch] Google scrape failed: {}", e.getMessage());
            return null;
        }
    }

    /** Parse Google search result snippets from the HTML. */
    private String parseGoogleResults(String html, String query) {
        List<SearchResult> results = new ArrayList<>();

        // Google wraps results in <a href="/url?q=..."> or <a href="https://...">
        Pattern linkPattern = Pattern.compile(
                "<a[^>]+href=\"/url\\?q=([^\"&]+)\"[^>]*>(.*?)</a>",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

        Matcher m = linkPattern.matcher(html);
        while (m.find() && results.size() < 10) {
            String link = m.group(1).trim();
            String titleHtml = m.group(2).trim();
            String title = stripTags(titleHtml).trim();

            // Skip Google internal links
            if (link.startsWith("/") || link.contains("google.com") ||
                    link.contains("accounts.google") || title.isBlank()) continue;

            try {
                link = java.net.URLDecoder.decode(link, StandardCharsets.UTF_8);
            } catch (Exception ignored) {}

            results.add(new SearchResult(title, "", link));
        }

        if (results.isEmpty()) return null;

        return formatResults(results, query);
    }

    // ═══ Helpers ═══

    private String formatResults(List<SearchResult> results, String query) {
        StringBuilder sb = new StringBuilder();
        sb.append("Search results for: ").append(query).append("\n\n");

        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            sb.append(i + 1).append(". ").append(r.title).append("\n");
            if (!r.snippet.isBlank()) {
                sb.append("   ").append(r.snippet).append("\n");
            }
            sb.append("   ").append(r.link).append("\n\n");
        }

        return sb.toString().trim();
    }

    private String stripTags(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", "")
                .replaceAll("&nbsp;", " ").replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<").replaceAll("&gt;", ">")
                .replaceAll("&quot;", "\"").replaceAll("&#39;", "'")
                .replaceAll("\\s+", " ").trim();
    }

    private String fetchHtml(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml")
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode() == 200 ? response.body() : null;
    }

    private record SearchResult(String title, String snippet, String link) {}
}
