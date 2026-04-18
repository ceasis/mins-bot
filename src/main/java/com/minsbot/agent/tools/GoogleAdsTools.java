package com.minsbot.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minsbot.GoogleIntegrationOAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Google Ads tools — list accessible customer accounts and run basic GAQL queries.
 *
 * <p>Requires the "googleads" integration to be connected in the Integrations tab AND
 * a Google Ads developer token configured. Get a developer token at
 * https://ads.google.com/aw/apicenter — set it as {@code app.googleads.developer-token}
 * (or env var {@code GOOGLE_ADS_DEVELOPER_TOKEN}) in application-secrets.properties.</p>
 */
@Component
public class GoogleAdsTools {

    private static final Logger log = LoggerFactory.getLogger(GoogleAdsTools.class);
    private static final String API = "https://googleads.googleapis.com/v17";

    private final GoogleIntegrationOAuthService oauth;
    private final ToolExecutionNotifier notifier;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    @Value("${app.googleads.developer-token:${GOOGLE_ADS_DEVELOPER_TOKEN:}}")
    private String developerToken;

    public GoogleAdsTools(GoogleIntegrationOAuthService oauth, ToolExecutionNotifier notifier) {
        this.oauth = oauth;
        this.notifier = notifier;
    }

    private String preflight() {
        String token = oauth.getValidAccessToken("googleads");
        if (token == null) return "Google Ads not connected. Connect it in the Integrations tab.";
        if (developerToken == null || developerToken.isBlank()) {
            return "Google Ads developer token missing. Set app.googleads.developer-token in application-secrets.properties "
                    + "(get one at https://ads.google.com/aw/apicenter).";
        }
        return null;
    }

    @Tool(description = "List Google Ads customer accounts (customer IDs) accessible with the current connection. "
            + "Returns each resource name like 'customers/1234567890'. Use this first to find the customer ID "
            + "for other queries.")
    public String listGoogleAdsCustomers() {
        notifier.notify("Listing Google Ads customers...");
        String err = preflight();
        if (err != null) return err;
        String token = oauth.getValidAccessToken("googleads");

        try {
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(API + "/customers:listAccessibleCustomers"))
                    .header("Authorization", "Bearer " + token)
                    .header("developer-token", developerToken)
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return "Google Ads API error (HTTP " + resp.statusCode() + "): " + resp.body();
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode names = root.get("resourceNames");
            if (names == null || !names.isArray() || names.isEmpty()) {
                return "No Google Ads accounts accessible with this connection.";
            }
            StringBuilder sb = new StringBuilder("Accessible Google Ads customers:\n\n");
            int i = 1;
            for (JsonNode n : names) {
                sb.append(i++).append(". ").append(n.asText()).append("\n");
            }
            sb.append("\nUse the customer ID (e.g. 1234567890) in other Google Ads queries.");
            return sb.toString();
        } catch (Exception e) {
            log.error("[GoogleAds] listCustomers failed: {}", e.getMessage(), e);
            return "Failed to list Google Ads customers: " + e.getMessage();
        }
    }

    @Tool(description = "Run a GAQL (Google Ads Query Language) query against a specific customer account. "
            + "Example queries: "
            + "'SELECT campaign.name, metrics.clicks, metrics.cost_micros FROM campaign WHERE segments.date DURING LAST_30_DAYS', "
            + "'SELECT customer.id, customer.descriptive_name FROM customer'. "
            + "Returns the raw JSON results (truncated to 10 KB).")
    public String runGoogleAdsQuery(
            @ToolParam(description = "Customer ID (just the number, e.g. '1234567890')") String customerId,
            @ToolParam(description = "GAQL query string") String gaqlQuery) {

        if (customerId == null || customerId.isBlank()) return "customerId is required.";
        if (gaqlQuery == null || gaqlQuery.isBlank()) return "Query is required.";

        notifier.notify("Running Google Ads query for customer " + customerId + "...");
        String err = preflight();
        if (err != null) return err;
        String token = oauth.getValidAccessToken("googleads");

        try {
            String cid = customerId.trim().replaceAll("[^0-9]", "");
            String body = "{\"query\":\"" + gaqlQuery.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(API + "/customers/" + cid + "/googleAds:searchStream"))
                    .header("Authorization", "Bearer " + token)
                    .header("developer-token", developerToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return "Google Ads API error (HTTP " + resp.statusCode() + "): " + resp.body();
            }
            String out = resp.body();
            if (out.length() > 10000) out = out.substring(0, 10000) + "\n\n[...truncated]";
            return out;
        } catch (Exception e) {
            log.error("[GoogleAds] query failed: {}", e.getMessage(), e);
            return "Failed to run Google Ads query: " + e.getMessage();
        }
    }
}
