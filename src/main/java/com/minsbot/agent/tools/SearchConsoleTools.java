package com.minsbot.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minsbot.GoogleIntegrationOAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;

/**
 * Google Search Console tools — list verified sites and query search analytics.
 * Requires the "searchconsole" integration to be connected in the Integrations tab.
 */
@Component
public class SearchConsoleTools {

    private static final Logger log = LoggerFactory.getLogger(SearchConsoleTools.class);
    private static final String API = "https://searchconsole.googleapis.com/webmasters/v3";

    private final GoogleIntegrationOAuthService oauth;
    private final ToolExecutionNotifier notifier;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    public SearchConsoleTools(GoogleIntegrationOAuthService oauth, ToolExecutionNotifier notifier) {
        this.oauth = oauth;
        this.notifier = notifier;
    }

    @Tool(description = "List the sites (properties) verified in Google Search Console. "
            + "Requires the Search Console integration to be connected.")
    public String listSearchConsoleSites() {
        notifier.notify("Listing Search Console sites...");
        String token = oauth.getValidAccessToken("searchconsole");
        if (token == null) return "Search Console not connected. Connect it in the Integrations tab.";

        try {
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(API + "/sites"))
                    .header("Authorization", "Bearer " + token)
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return "Search Console API error (HTTP " + resp.statusCode() + "): " + resp.body();
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode entries = root.get("siteEntry");
            if (entries == null || !entries.isArray() || entries.isEmpty()) {
                return "No Search Console sites found for this account.";
            }
            StringBuilder sb = new StringBuilder("Search Console sites:\n\n");
            int i = 1;
            for (JsonNode e : entries) {
                sb.append(i++).append(". ").append(e.path("siteUrl").asText())
                  .append("  (").append(e.path("permissionLevel").asText()).append(")\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.error("[SearchConsole] listSites failed: {}", e.getMessage(), e);
            return "Failed to fetch sites: " + e.getMessage();
        }
    }

    @Tool(description = "Query Google Search Console analytics for a site over the last N days. "
            + "Returns top queries (search terms) with clicks, impressions, CTR, and average position. "
            + "Use when the user asks about SEO performance, top search terms, or impressions/clicks.")
    public String getSearchAnalytics(
            @ToolParam(description = "Site URL exactly as registered in Search Console (e.g. 'https://example.com/' or 'sc-domain:example.com')") String siteUrl,
            @ToolParam(description = "Number of days back to query (1-90)") double daysBack,
            @ToolParam(description = "Max rows to return (1-50)") double rowLimit) {

        if (siteUrl == null || siteUrl.isBlank()) return "Site URL is required.";
        int days = Math.max(1, Math.min(90, (int) Math.round(daysBack)));
        int rows = Math.max(1, Math.min(50, (int) Math.round(rowLimit)));
        notifier.notify("Querying Search Console for " + siteUrl + " (last " + days + " days)...");

        String token = oauth.getValidAccessToken("searchconsole");
        if (token == null) return "Search Console not connected.";

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days);

        String body = "{"
                + "\"startDate\":\"" + startDate + "\","
                + "\"endDate\":\"" + endDate + "\","
                + "\"dimensions\":[\"query\"],"
                + "\"rowLimit\":" + rows
                + "}";

        try {
            String url = API + "/sites/" + java.net.URLEncoder.encode(siteUrl, java.nio.charset.StandardCharsets.UTF_8)
                    + "/searchAnalytics/query";
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return "Search Console API error (HTTP " + resp.statusCode() + "): " + resp.body();
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode r = root.get("rows");
            if (r == null || !r.isArray() || r.isEmpty()) {
                return "No analytics data for " + siteUrl + " in the last " + days + " days.";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Top ").append(r.size()).append(" search queries for ").append(siteUrl)
              .append(" (").append(startDate).append(" → ").append(endDate).append("):\n\n");
            int i = 1;
            for (JsonNode row : r) {
                String query = row.path("keys").get(0).asText();
                int clicks = row.path("clicks").asInt();
                int imps = row.path("impressions").asInt();
                double ctr = row.path("ctr").asDouble() * 100;
                double pos = row.path("position").asDouble();
                sb.append(i++).append(". \"").append(query).append("\"")
                  .append(" — clicks: ").append(clicks)
                  .append(", imps: ").append(imps)
                  .append(", CTR: ").append(String.format("%.1f%%", ctr))
                  .append(", avg pos: ").append(String.format("%.1f", pos))
                  .append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.error("[SearchConsole] query failed: {}", e.getMessage(), e);
            return "Failed to query Search Console: " + e.getMessage();
        }
    }
}
