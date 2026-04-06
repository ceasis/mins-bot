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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * Gmail API tools — read emails via Google Gmail API.
 * Requires the "gmail" integration to be connected in the Integrations tab.
 * This complements EmailTools (IMAP/SMTP) by using the OAuth-based Gmail API.
 */
@Component
public class GmailApiTools {

    private static final Logger log = LoggerFactory.getLogger(GmailApiTools.class);
    private static final String GMAIL_API = "https://gmail.googleapis.com/gmail/v1/users/me";

    private final GoogleIntegrationOAuthService oauthService;
    private final ToolExecutionNotifier notifier;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public GmailApiTools(GoogleIntegrationOAuthService oauthService,
                         ToolExecutionNotifier notifier) {
        this.oauthService = oauthService;
        this.notifier = notifier;
    }

    @Tool(description = "Get unread emails from Gmail via Google API. Returns sender, subject, date, " +
            "and a snippet of each unread email. Use for morning briefings, email checks, or when the user asks " +
            "'check my email' or 'any new emails'. Requires Gmail integration connected in Integrations tab. " +
            "If Gmail API is not connected, falls back to suggesting IMAP readInbox.")
    public String getUnreadEmails(
            @ToolParam(description = "Maximum number of unread emails to fetch (1-20)") double maxResults) {
        int max = Math.max(1, Math.min(20, (int) Math.round(maxResults)));
        notifier.notify("Checking Gmail for unread emails...");

        String accessToken = oauthService.getValidAccessToken("gmail");
        if (accessToken == null) {
            return "Gmail API not connected. Please connect Gmail in the Integrations tab, or use readInbox (IMAP) if IMAP is configured.";
        }

        try {
            // List unread messages
            String listUrl = GMAIL_API + "/messages"
                    + "?q=" + URLEncoder.encode("is:unread", StandardCharsets.UTF_8)
                    + "&maxResults=" + max;

            HttpRequest listReq = HttpRequest.newBuilder()
                    .uri(URI.create(listUrl))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();

            HttpResponse<String> listResp = httpClient.send(listReq, HttpResponse.BodyHandlers.ofString());
            if (listResp.statusCode() != 200) {
                return "Gmail API error (HTTP " + listResp.statusCode() + "). Try reconnecting in Integrations tab.";
            }

            JsonNode listRoot = mapper.readTree(listResp.body());
            JsonNode messages = listRoot.get("messages");
            int resultSize = listRoot.has("resultSizeEstimate") ? listRoot.get("resultSizeEstimate").asInt() : 0;

            if (messages == null || !messages.isArray() || messages.isEmpty()) {
                return "No unread emails in Gmail.";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Unread emails (").append(Math.min(messages.size(), max))
              .append(" of ~").append(resultSize).append(" total):\n\n");

            int count = 0;
            for (JsonNode msg : messages) {
                if (count >= max) break;
                String msgId = msg.get("id").asText();
                String detail = fetchMessageDetail(accessToken, msgId);
                if (detail != null) {
                    count++;
                    sb.append(count).append(". ").append(detail).append("\n\n");
                }
            }

            return sb.toString().trim();

        } catch (Exception e) {
            log.error("[GmailApi] Error fetching unread emails: {}", e.getMessage(), e);
            return "Failed to fetch Gmail: " + e.getMessage();
        }
    }

    @Tool(description = "Get recent emails from Gmail (read and unread). Returns sender, subject, date, " +
            "and snippet. Use when user asks for recent mail overview, not just unread.")
    public String getRecentEmails(
            @ToolParam(description = "Maximum number of emails to fetch (1-20)") double maxResults) {
        int max = Math.max(1, Math.min(20, (int) Math.round(maxResults)));
        notifier.notify("Fetching recent Gmail messages...");

        String accessToken = oauthService.getValidAccessToken("gmail");
        if (accessToken == null) {
            return "Gmail API not connected. Please connect Gmail in the Integrations tab.";
        }

        try {
            String listUrl = GMAIL_API + "/messages?maxResults=" + max;

            HttpRequest listReq = HttpRequest.newBuilder()
                    .uri(URI.create(listUrl))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();

            HttpResponse<String> listResp = httpClient.send(listReq, HttpResponse.BodyHandlers.ofString());
            if (listResp.statusCode() != 200) {
                return "Gmail API error (HTTP " + listResp.statusCode() + ").";
            }

            JsonNode listRoot = mapper.readTree(listResp.body());
            JsonNode messages = listRoot.get("messages");
            if (messages == null || !messages.isArray() || messages.isEmpty()) {
                return "No emails found.";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Recent emails:\n\n");

            int count = 0;
            for (JsonNode msg : messages) {
                if (count >= max) break;
                String msgId = msg.get("id").asText();
                String detail = fetchMessageDetail(accessToken, msgId);
                if (detail != null) {
                    count++;
                    sb.append(count).append(". ").append(detail).append("\n\n");
                }
            }

            return sb.toString().trim();

        } catch (Exception e) {
            log.error("[GmailApi] Error fetching recent emails: {}", e.getMessage(), e);
            return "Failed to fetch Gmail: " + e.getMessage();
        }
    }

    @Tool(description = "Get a count of unread emails in Gmail. Quick check without fetching details.")
    public String getUnreadCount() {
        notifier.notify("Counting unread emails...");

        String accessToken = oauthService.getValidAccessToken("gmail");
        if (accessToken == null) {
            return "Gmail API not connected.";
        }

        try {
            // Use labels/UNREAD to get count
            String url = GMAIL_API + "/labels/UNREAD";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                // Fallback: count via messages list
                return countViaList(accessToken);
            }

            JsonNode root = mapper.readTree(resp.body());
            int total = root.has("messagesTotal") ? root.get("messagesTotal").asInt() : 0;
            return "You have " + total + " unread email" + (total != 1 ? "s" : "") + ".";

        } catch (Exception e) {
            return "Failed to count unread emails: " + e.getMessage();
        }
    }

    private String countViaList(String accessToken) {
        try {
            String url = GMAIL_API + "/messages?q=" + URLEncoder.encode("is:unread", StandardCharsets.UTF_8)
                    + "&maxResults=1";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(resp.body());
            int count = root.has("resultSizeEstimate") ? root.get("resultSizeEstimate").asInt() : 0;
            return "You have approximately " + count + " unread email" + (count != 1 ? "s" : "") + ".";
        } catch (Exception e) {
            return "Failed to count emails.";
        }
    }

    private String fetchMessageDetail(String accessToken, String messageId) {
        try {
            String url = GMAIL_API + "/messages/" + messageId + "?format=metadata"
                    + "&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;

            JsonNode root = mapper.readTree(resp.body());
            String snippet = root.has("snippet") ? root.get("snippet").asText() : "";
            String from = "", subject = "", date = "";

            JsonNode headers = root.path("payload").path("headers");
            if (headers.isArray()) {
                for (JsonNode h : headers) {
                    String name = h.get("name").asText();
                    String value = h.get("value").asText();
                    if ("From".equalsIgnoreCase(name)) from = value;
                    else if ("Subject".equalsIgnoreCase(name)) subject = value;
                    else if ("Date".equalsIgnoreCase(name)) date = formatDate(value);
                }
            }

            return "From: " + from + "\n   Subject: " + subject
                    + "\n   Date: " + date
                    + "\n   Preview: " + (snippet.length() > 150 ? snippet.substring(0, 150) + "..." : snippet);

        } catch (Exception e) {
            log.debug("[GmailApi] Failed to fetch message {}: {}", messageId, e.getMessage());
            return null;
        }
    }

    private String formatDate(String rawDate) {
        try {
            // Gmail date format: "Mon, 6 Apr 2026 08:30:00 +0800"
            java.time.ZonedDateTime zdt = java.time.ZonedDateTime.parse(rawDate,
                    java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
            return zdt.withZoneSameInstant(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("MMM d, h:mm a"));
        } catch (Exception e) {
            return rawDate;
        }
    }
}
