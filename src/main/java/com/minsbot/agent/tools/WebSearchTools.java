package com.minsbot.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Web search for the {@code searchWeb} tool: Serper, SerpAPI, or free DuckDuckGo / Google HTML fallback.
 */
@Component
public class WebSearchTools {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTools.class);

    private static final String SERPER_URL = "https://google.serper.dev/search";
    private static final String SERPAPI_URL = "https://serpapi.com/search.json";

    private final ToolExecutionNotifier notifier;
    private final String providerRaw;
    private final String serperApiKey;
    private final String serpApiKey;

    /** Local headless browser. When present, used as the primary text-search
     *  provider so we don't spend money on Serper/SerpAPI for every query. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PlaywrightService playwright;

    // Circuit breaker: after 2 consecutive Playwright failures, skip it for
    // 5 minutes. Prevents wasting ~10s per query when Chromium is wedged.
    private final java.util.concurrent.atomic.AtomicInteger consecutivePlaywrightFails =
            new java.util.concurrent.atomic.AtomicInteger();
    private volatile long playwrightSkipUntilEpochMs = 0L;
    private static final int PLAYWRIGHT_FAIL_THRESHOLD = 2;
    private static final long PLAYWRIGHT_COOLDOWN_MS = 5 * 60 * 1000L;

    private void tripPlaywrightBreaker() {
        int n = consecutivePlaywrightFails.incrementAndGet();
        if (n >= PLAYWRIGHT_FAIL_THRESHOLD) {
            playwrightSkipUntilEpochMs = System.currentTimeMillis() + PLAYWRIGHT_COOLDOWN_MS;
            consecutivePlaywrightFails.set(0);
            log.warn("[WebSearch] Playwright circuit breaker tripped — skipping for 5 min");
        }
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final ObjectMapper jsonMapper = new ObjectMapper();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.minsbot.offline.OfflineModeService offlineMode;

    public WebSearchTools(
            ToolExecutionNotifier notifier,
            @Value("${app.web-search.provider:auto}") String providerRaw,
            @Value("${app.web-search.serper-api-key:}") String serperApiKey,
            @Value("${app.web-search.serpapi-api-key:}") String serpApiKey) {
        this.notifier = notifier;
        this.providerRaw = providerRaw != null ? providerRaw : "auto";
        this.serperApiKey = serperApiKey != null ? serperApiKey : "";
        this.serpApiKey = serpApiKey != null ? serpApiKey : "";
    }

    @PostConstruct
    void logProvider() {
        String p = providerRaw.trim().toLowerCase();
        boolean hasSerper = !serperApiKey.isBlank();
        boolean hasSerp = !serpApiKey.isBlank();
        log.info("[WebSearch] provider={}, serperKey={}, serpapiKey={}",
                p, hasSerper, hasSerp);
    }

    @Tool(description = "CANONICAL way to ANSWER from the web — returns text results (titles, snippets, "
            + "links) directly in chat. Cheap, fast, no browser opens. Use whenever the user asks a "
            + "factual / how-to / news / price question — 'what's the weather in X', 'who won Y', "
            + "'how do I Z', 'find articles on Q'. "
            + "Backed by Serper / SerpAPI when configured, else DuckDuckGo HTML. "
            + "DO NOT use to OPEN search results in the user's browser — that's browserTools.searchGoogle. "
            + "DO NOT use for image downloads — that's webScraperTools.searchAndDownloadImages.")
    public String searchWeb(
            @ToolParam(description = "Search query, e.g. 'weather Tokyo April 2026'") String query) {
        if (offlineMode != null && offlineMode.isOffline()) {
            return "Offline mode is ON — web search blocked. Nothing left the machine. "
                    + "Toggle via the title-bar shield icon to re-enable.";
        }
        notifier.notify("Searching: " + query);
        try {
            String p = providerRaw.trim().toLowerCase();

            if ("serper".equals(p)) {
                String r = searchSerper(query);
                return r != null ? r : fallbackDuckAndGoogle(query);
            }
            if ("serpapi".equals(p)) {
                String r = searchSerpApi(query);
                return r != null ? r : fallbackDuckAndGoogle(query);
            }
            if ("ddg".equals(p)) {
                return fallbackDuckAndGoogle(query);
            }

            // auto (default): LOCAL browser first (free, no API), Serper/SerpAPI only as fallbacks.
            //   1. Playwright Bing (headless, local, no key)
            //   2. Serper API (paid, only if configured)
            //   3. SerpAPI (paid, only if configured)
            //   4. DDG / Google HTML scrape via plain HTTP
            if (playwright != null && System.currentTimeMillis() >= playwrightSkipUntilEpochMs) {
                try {
                    String r = playwright.searchTextResults(query, 10);
                    if (r != null && !r.isBlank()) {
                        consecutivePlaywrightFails.set(0);
                        log.info("[WebSearch] Playwright(Bing) returned results for '{}'",
                                query.length() <= 60 ? query : query.substring(0, 60) + "…");
                        return r;
                    }
                    tripPlaywrightBreaker();
                } catch (Exception e) {
                    log.warn("[WebSearch] Playwright text search failed: {}", e.getMessage());
                    tripPlaywrightBreaker();
                }
            } else if (playwright != null) {
                log.debug("[WebSearch] Skipping Playwright (circuit breaker open until {})",
                        playwrightSkipUntilEpochMs);
            }
            if (!serperApiKey.isBlank()) {
                String r = searchSerper(query);
                if (r != null) return r;
            }
            if (!serpApiKey.isBlank()) {
                String r = searchSerpApi(query);
                if (r != null) return r;
            }
            return fallbackDuckAndGoogle(query);
        } catch (Exception e) {
            log.warn("[WebSearch] Failed for '{}': {}", query, e.getMessage());
            return "Search failed: " + e.getMessage();
        }
    }

    // @Tool removed — duplicate of webScraperTools.fetchPageText. Method retained for any
    // Java-side caller still depending on it. The LLM should use webScraperTools.fetchPageText.
    public String readWebPage(
            @ToolParam(description = "Full URL, e.g. 'https://example.com/article'") String url) {
        if (offlineMode != null && offlineMode.isOffline()) {
            return "Offline mode is ON — web fetch blocked. Nothing left the machine.";
        }
        notifier.notify("Reading: " + url);
        try {
            String html = fetchHtml(url);
            if (html == null || html.isBlank()) return "Could not fetch page.";

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

    // ═══ Serper (Google JSON API) ═══

    private String searchSerper(String query) {
        if (serperApiKey.isBlank()) return null;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("q", query);
            body.put("num", 10);
            String jsonBody = jsonMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(SERPER_URL))
                    .header("X-API-KEY", serperApiKey.trim())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(20))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[WebSearch] Serper HTTP {}", response.statusCode());
                return null;
            }
            JsonNode root = jsonMapper.readTree(response.body());
            if (root.has("message") && root.get("message").isTextual()) {
                log.warn("[WebSearch] Serper error: {}", root.get("message").asText());
                return null;
            }
            JsonNode organic = root.get("organic");
            if (organic == null || !organic.isArray() || organic.isEmpty()) {
                return null;
            }
            List<SearchResult> results = new ArrayList<>();
            for (JsonNode item : organic) {
                if (results.size() >= 10) break;
                String title = textOrEmpty(item.get("title"));
                String link = textOrEmpty(item.get("link"));
                String snippet = textOrEmpty(item.get("snippet"));
                if (!title.isBlank() && !link.isBlank()) {
                    results.add(new SearchResult(title, snippet, link));
                }
            }
            if (results.isEmpty()) return null;
            log.info("[WebSearch] Serper returned {} results", results.size());
            return formatResults(results, query, "Serper");
        } catch (Exception e) {
            log.warn("[WebSearch] Serper failed: {}", e.getMessage());
            return null;
        }
    }

    // ═══ SerpAPI ═══

    private String searchSerpApi(String query) {
        if (serpApiKey.isBlank()) return null;
        try {
            String q = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String key = URLEncoder.encode(serpApiKey.trim(), StandardCharsets.UTF_8);
            String url = SERPAPI_URL + "?engine=google&q=" + q + "&num=10&api_key=" + key;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .GET()
                    .timeout(Duration.ofSeconds(25))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[WebSearch] SerpAPI HTTP {}", response.statusCode());
                return null;
            }
            JsonNode root = jsonMapper.readTree(response.body());
            if (root.has("error")) {
                log.warn("[WebSearch] SerpAPI error: {}", root.get("error").asText(""));
                return null;
            }
            JsonNode organic = root.get("organic_results");
            if (organic == null || !organic.isArray() || organic.isEmpty()) {
                return null;
            }
            List<SearchResult> results = new ArrayList<>();
            for (JsonNode item : organic) {
                if (results.size() >= 10) break;
                String title = textOrEmpty(item.get("title"));
                String link = textOrEmpty(item.get("link"));
                String snippet = textOrEmpty(item.get("snippet"));
                if (!title.isBlank() && !link.isBlank()) {
                    results.add(new SearchResult(title, snippet, link));
                }
            }
            if (results.isEmpty()) return null;
            log.info("[WebSearch] SerpAPI returned {} results", results.size());
            return formatResults(results, query, "SerpAPI");
        } catch (Exception e) {
            log.warn("[WebSearch] SerpAPI failed: {}", e.getMessage());
            return null;
        }
    }

    private static String textOrEmpty(JsonNode n) {
        return n == null || !n.isTextual() ? "" : n.asText("").trim();
    }

    // ═══ DuckDuckGo + Google scrape ═══

    private String fallbackDuckAndGoogle(String query) {
        String results = searchDuckDuckGo(query);
        if (results != null && !results.isBlank()) {
            log.info("[WebSearch] DDG returned results for: {}", query);
            return results;
        }
        results = searchGoogleScrape(query);
        if (results != null && !results.isBlank()) {
            log.info("[WebSearch] Google scrape returned results for: {}", query);
            return results;
        }
        return "No results found for: " + query;
    }

    private String searchDuckDuckGo(String query) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://html.duckduckgo.com/html/?q=" + encoded;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
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
            return parseDdgResults(response.body(), query);
        } catch (Exception e) {
            log.warn("[WebSearch] DDG search failed: {}", e.getMessage());
            return null;
        }
    }

    private String parseDdgResults(String html, String query) {
        List<SearchResult> results = new ArrayList<>();
        Pattern resultBlock = Pattern.compile(
                "<a[^>]+class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>.*?"
                        + "class=\"result__snippet\"[^>]*>(.*?)</",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

        Matcher m = resultBlock.matcher(html);
        while (m.find() && results.size() < 10) {
            String link = m.group(1).trim();
            String title = stripTags(m.group(2)).trim();
            String snippet = stripTags(m.group(3)).trim();

            if (link.contains("uddg=")) {
                try {
                    String decoded = java.net.URLDecoder.decode(
                            link.substring(link.indexOf("uddg=") + 5), StandardCharsets.UTF_8);
                    if (decoded.contains("&")) decoded = decoded.substring(0, decoded.indexOf("&"));
                    link = decoded;
                } catch (Exception ignored) { /* keep link */ }
            }

            if (!title.isBlank() && !link.isBlank()) {
                results.add(new SearchResult(title, snippet, link));
            }
        }

        if (results.isEmpty()) return null;
        return formatResults(results, query, "DuckDuckGo");
    }

    private String searchGoogleScrape(String query) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://www.google.com/search?q=" + encoded + "&hl=en&num=10";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
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

    private String parseGoogleResults(String html, String query) {
        List<SearchResult> results = new ArrayList<>();
        Pattern linkPattern = Pattern.compile(
                "<a[^>]+href=\"/url\\?q=([^\"&]+)\"[^>]*>(.*?)</a>",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

        Matcher m = linkPattern.matcher(html);
        while (m.find() && results.size() < 10) {
            String link = m.group(1).trim();
            String titleHtml = m.group(2).trim();
            String title = stripTags(titleHtml).trim();

            if (link.startsWith("/") || link.contains("google.com")
                    || link.contains("accounts.google") || title.isBlank()) continue;

            try {
                link = java.net.URLDecoder.decode(link, StandardCharsets.UTF_8);
            } catch (Exception ignored) { /* keep */ }

            results.add(new SearchResult(title, "", link));
        }

        if (results.isEmpty()) return null;
        return formatResults(results, query, "Google (HTML)");
    }

    private String formatResults(List<SearchResult> results, String query, String source) {
        StringBuilder sb = new StringBuilder();
        sb.append("Search results for: ").append(query).append(" (via ").append(source).append(")\n\n");

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
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml")
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode() == 200 ? response.body() : null;
    }

    private record SearchResult(String title, String snippet, String link) {}
}
