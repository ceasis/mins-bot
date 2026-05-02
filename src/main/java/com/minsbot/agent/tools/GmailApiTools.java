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
import java.util.Locale;
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

    @Tool(description = "CANONICAL way to read UNREAD emails. Uses the Gmail API via OAuth. Returns "
            + "sender, subject, date, snippet of each unread message. Use for morning briefings, "
            + "email checks, 'check my email', 'any new emails', 'what's new in my inbox', "
            + "'what came in overnight'. Single-account default; for multi-account use "
            + "getUnreadEmailsFromAll. Requires Gmail integration connected in Integrations tab.")
    @com.minsbot.offline.RequiresOnline("Gmail unread emails")
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
                return formatApiError("getUnreadEmails", listResp);
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

    @Tool(description = "CANONICAL way to read recent emails (read AND unread). Gmail API via OAuth. "
            + "Use for 'show me my recent emails', 'what's been happening in my inbox', "
            + "'last 10 emails', 'summarize today's mail'. Differs from getUnreadEmails by including "
            + "already-read messages — pick this when the user wants context, not just new mail. "
            + "DO NOT use emailTools.readInbox (IMAP) when Gmail is configured.")
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
                return formatApiError("getRecentEmails", listResp);
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

    @Tool(description = "Read the FULL body of a specific Gmail message by ID, including sender, recipients, date, "
            + "subject, and the complete plain-text body (HTML stripped). Use when the user asks to 'read the email', "
            + "'what does this email say', 'show me the content of that message', or after getUnreadEmails to dig into a specific one. "
            + "Get the message ID from getUnreadEmails / searchEmails / getRecentEmails (each result contains an id field).")
    public String readEmail(
            @ToolParam(description = "Gmail message ID (e.g. '18f4c2a90ab12def'). Get this from getUnreadEmails or searchEmails.")
            String messageId) {
        if (messageId == null || messageId.isBlank()) return "Message ID is required.";
        notifier.notify("Reading email " + messageId + "...");

        String accessToken = oauthService.getValidAccessToken("gmail");
        if (accessToken == null) {
            return "Gmail API not connected. Connect Gmail in the Integrations tab first.";
        }

        try {
            // format=full returns payload with MIME parts including bodies
            String url = GMAIL_API + "/messages/" + messageId + "?format=full";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return formatApiError("readEmail", resp);
            }

            JsonNode root = mapper.readTree(resp.body());
            StringBuilder sb = new StringBuilder();

            // Headers
            String from = "", to = "", cc = "", subject = "", date = "";
            JsonNode headers = root.path("payload").path("headers");
            if (headers.isArray()) {
                for (JsonNode h : headers) {
                    String n = h.get("name").asText();
                    String v = h.get("value").asText();
                    if ("From".equalsIgnoreCase(n)) from = v;
                    else if ("To".equalsIgnoreCase(n)) to = v;
                    else if ("Cc".equalsIgnoreCase(n)) cc = v;
                    else if ("Subject".equalsIgnoreCase(n)) subject = v;
                    else if ("Date".equalsIgnoreCase(n)) date = formatDate(v);
                }
            }
            sb.append("From: ").append(from).append("\n");
            sb.append("To: ").append(to).append("\n");
            if (!cc.isBlank()) sb.append("Cc: ").append(cc).append("\n");
            sb.append("Date: ").append(date).append("\n");
            sb.append("Subject: ").append(subject).append("\n");
            sb.append("\n");

            // Body: walk MIME parts, prefer text/plain, fall back to text/html (stripped)
            String body = extractBody(root.path("payload"));
            if (body == null || body.isBlank()) {
                // Fallback — some messages only have a snippet
                body = root.has("snippet") ? root.get("snippet").asText() : "(empty body)";
            }

            // Cap at 10,000 chars to avoid blowing the chat response size
            if (body.length() > 10000) {
                body = body.substring(0, 10000) + "\n\n[...truncated — " + (body.length() - 10000) + " more chars]";
            }
            sb.append(body);

            return sb.toString();
        } catch (Exception e) {
            log.error("[GmailApi] readEmail failed: {}", e.getMessage(), e);
            return "Failed to read email: " + e.getMessage();
        }
    }

    @Tool(description = "Search Gmail with a query string (Gmail search syntax: from:, to:, subject:, has:attachment, "
            + "newer_than:7d, label:work, etc.) and return matching message IDs + headers. "
            + "Example queries: 'from:boss@company.com', 'subject:invoice newer_than:30d', 'has:attachment pdf'. "
            + "Use when the user asks to 'find the email from X', 'show emails about Y', 'search my inbox for Z'. "
            + "Combine with readEmail(id) to read the full body of a specific result.")
    public String searchEmails(
            @ToolParam(description = "Gmail search query, e.g. 'from:alice subject:report newer_than:14d'") String query,
            @ToolParam(description = "Max results to return (1-20)") double maxResults) {
        if (query == null || query.isBlank()) return "Search query is required.";
        int max = Math.max(1, Math.min(20, (int) Math.round(maxResults)));
        notifier.notify("Searching Gmail: " + query);

        String accessToken = oauthService.getValidAccessToken("gmail");
        if (accessToken == null) return "Gmail API not connected.";

        try {
            String url = GMAIL_API + "/messages?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&maxResults=" + max;
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return formatApiError("searchEmails", resp);

            JsonNode root = mapper.readTree(resp.body());
            JsonNode messages = root.get("messages");
            if (messages == null || !messages.isArray() || messages.isEmpty()) {
                return "No emails match query: " + query;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Found ").append(messages.size()).append(" email(s) matching \"").append(query).append("\":\n\n");

            int count = 0;
            for (JsonNode msg : messages) {
                if (count >= max) break;
                String msgId = msg.get("id").asText();
                String detail = fetchMessageDetail(accessToken, msgId);
                if (detail != null) {
                    count++;
                    sb.append(count).append(". ID: ").append(msgId).append("\n   ").append(detail).append("\n\n");
                }
            }
            sb.append("To read the full body of any result, call readEmail(<id>).");
            return sb.toString();
        } catch (Exception e) {
            log.error("[GmailApi] searchEmails failed: {}", e.getMessage(), e);
            return "Search failed: " + e.getMessage();
        }
    }

    /**
     * Walk Gmail's nested MIME payload tree, decode base64url bodies,
     * prefer text/plain parts and fall back to HTML (with tags stripped).
     */
    private String extractBody(JsonNode payload) {
        if (payload == null || payload.isMissingNode()) return null;

        // Try text/plain first at current level
        String mimeType = payload.path("mimeType").asText("");
        if (mimeType.startsWith("text/plain")) {
            String decoded = decodeBody(payload.path("body"));
            if (decoded != null && !decoded.isBlank()) return decoded;
        }

        // Recurse into parts
        JsonNode parts = payload.get("parts");
        if (parts != null && parts.isArray()) {
            // First pass: find text/plain
            for (JsonNode p : parts) {
                if (p.path("mimeType").asText("").startsWith("text/plain")) {
                    String body = decodeBody(p.path("body"));
                    if (body != null && !body.isBlank()) return body;
                }
            }
            // Second pass: any multipart/* → recurse
            for (JsonNode p : parts) {
                String mt = p.path("mimeType").asText("");
                if (mt.startsWith("multipart/")) {
                    String body = extractBody(p);
                    if (body != null && !body.isBlank()) return body;
                }
            }
            // Third pass: text/html stripped
            for (JsonNode p : parts) {
                if (p.path("mimeType").asText("").startsWith("text/html")) {
                    String body = decodeBody(p.path("body"));
                    if (body != null && !body.isBlank()) return stripHtml(body);
                }
            }
        }

        // HTML at top level → strip
        if (mimeType.startsWith("text/html")) {
            String decoded = decodeBody(payload.path("body"));
            if (decoded != null && !decoded.isBlank()) return stripHtml(decoded);
        }

        return null;
    }

    private String decodeBody(JsonNode bodyNode) {
        if (bodyNode == null || bodyNode.isMissingNode()) return null;
        String data = bodyNode.path("data").asText(null);
        if (data == null) return null;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(data);
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private String stripHtml(String html) {
        return html.replaceAll("<script[^>]*>[\\s\\S]*?</script>", "")
                .replaceAll("<style[^>]*>[\\s\\S]*?</style>", "")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&quot;", "\"")
                .replaceAll("&#39;", "'")
                .replaceAll("\\s+", " ")
                .trim();
    }

    // ═══ Send (write) ═════════════════════════════════════════════════════

    @Tool(description = "CANONICAL way to send email. Uses the Gmail API via OAuth — no SMTP setup, "
            + "no browser autoclick, fully reliable. Use whenever the user says 'send an email to X', "
            + "'email Y about Z', 'reply to this', 'shoot a quick note to Q', 'draft and send'. "
            + "If multiple Gmail accounts are connected, pass fromAccount to pick which one; "
            + "otherwise the first connected account is used. Body can be plain text or HTML. "
            + "DO NOT use emailTools.sendEmail (SMTP-then-browser fallback, fragile) when Gmail is configured.")
    public String sendGmailViaApi(
            @ToolParam(description = "Recipient email(s), comma-separated") String to,
            @ToolParam(description = "Subject line") String subject,
            @ToolParam(description = "Email body (plain text)") String body,
            @ToolParam(description = "Sender email account (optional). Use '-' to auto-pick the first connected account.") String fromAccount) {

        if (to == null || to.isBlank()) return "Recipient (to) is required.";
        if (subject == null) subject = "";
        if (body == null) body = "";
        notifier.notify("Sending Gmail to " + to + "...");

        String accessToken;
        String senderLabel;
        if (fromAccount != null && !fromAccount.isBlank() && !fromAccount.equals("-")) {
            accessToken = oauthService.getValidAccessToken("gmail", fromAccount.trim());
            senderLabel = fromAccount.trim();
            if (accessToken == null) {
                return "Gmail account '" + fromAccount + "' is not connected. Use listGmailAccounts to see available accounts.";
            }
        } else {
            java.util.Map<String, String> tokens = oauthService.getAllValidAccessTokens("gmail");
            if (tokens.isEmpty()) {
                return "No Gmail accounts connected. Connect one in the Integrations tab.";
            }
            var first = tokens.entrySet().iterator().next();
            senderLabel = first.getKey();
            accessToken = first.getValue();
        }

        try {
            // Build RFC 2822 message
            StringBuilder raw = new StringBuilder();
            raw.append("From: ").append(senderLabel).append("\r\n");
            raw.append("To: ").append(to).append("\r\n");
            raw.append("Subject: ").append(subject).append("\r\n");
            raw.append("Content-Type: text/plain; charset=UTF-8\r\n");
            raw.append("MIME-Version: 1.0\r\n");
            raw.append("\r\n");
            raw.append(body);

            String base64url = Base64.getUrlEncoder()
                    .encodeToString(raw.toString().getBytes(StandardCharsets.UTF_8));

            String payload = "{\"raw\":\"" + base64url + "\"}";
            HttpResponse<String> resp = httpClient.send(HttpRequest.newBuilder()
                    .uri(URI.create(GMAIL_API + "/messages/send"))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build(), HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return "Gmail send failed (HTTP " + resp.statusCode() + "): " + resp.body();
            }
            JsonNode root = mapper.readTree(resp.body());
            String msgId = root.path("id").asText("?");
            return "Email sent from " + senderLabel + " to " + to + " (message id: " + msgId + ")";
        } catch (Exception e) {
            log.error("[GmailApi] send failed: {}", e.getMessage(), e);
            return "Failed to send email: " + e.getMessage();
        }
    }

    // ═══ Per-account tools (multi-Gmail support) ══════════════════════════

    @Tool(description = "List every Gmail account currently connected to the bot. "
            + "Returns each account's email address so the user or the AI can target a specific account "
            + "via getUnreadEmailsFromAccount, getRecentEmailsFromAccount, etc.")
    public String listGmailAccounts() {
        notifier.notify("Listing connected Gmail accounts...");
        java.util.Map<String, String> tokens = oauthService.getAllValidAccessTokens("gmail");
        if (tokens.isEmpty()) {
            return "No Gmail accounts connected. Connect one in the Integrations tab.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(tokens.size()).append(" Gmail account(s) connected:\n\n");
        int i = 1;
        for (String email : tokens.keySet()) {
            sb.append(i++).append(". ").append(email).append("\n");
        }
        return sb.toString().trim();
    }

    @Tool(description = "Get unread emails from a SPECIFIC Gmail account. Use when the user says "
            + "'check my work email', 'check alice@corp.com', or 'what's unread in my personal Gmail'. "
            + "Pass the exact email address as it appears in listGmailAccounts.")
    public String getUnreadEmailsFromAccount(
            @ToolParam(description = "Exact account email, e.g. 'me@gmail.com'") String accountEmail,
            @ToolParam(description = "Maximum unread emails to fetch (1-20)") double maxResults) {
        if (accountEmail == null || accountEmail.isBlank()) return "accountEmail is required.";
        int max = Math.max(1, Math.min(20, (int) Math.round(maxResults)));
        notifier.notify("Checking unread emails for " + accountEmail + "...");

        String token = oauthService.getValidAccessToken("gmail", accountEmail.trim());
        if (token == null) {
            return "Gmail not connected for " + accountEmail + ". Use listGmailAccounts to see available accounts.";
        }
        return fetchUnreadWithToken(token, max, accountEmail);
    }

    @Tool(description = "Aggregate unread emails from ALL connected Gmail accounts. Use for a unified morning "
            + "inbox sweep when the user has multiple Gmail accounts. Each email is labeled with the source account.")
    public String getUnreadEmailsFromAll(
            @ToolParam(description = "Max unread emails per account (1-10)") double perAccount) {
        int max = Math.max(1, Math.min(10, (int) Math.round(perAccount)));
        notifier.notify("Checking unread across all Gmail accounts...");
        java.util.Map<String, String> tokens = oauthService.getAllValidAccessTokens("gmail");
        if (tokens.isEmpty()) {
            return "No Gmail accounts connected.";
        }

        StringBuilder out = new StringBuilder();
        int totalShown = 0;
        for (java.util.Map.Entry<String, String> e : tokens.entrySet()) {
            String email = e.getKey();
            String token = e.getValue();
            String section = fetchUnreadWithToken(token, max, email);
            if (section != null && !section.isBlank() && !section.startsWith("No unread")) {
                out.append("═══ ").append(email).append(" ═══\n\n");
                out.append(section).append("\n\n");
                totalShown++;
            }
        }
        if (totalShown == 0) {
            return "No unread emails across " + tokens.size() + " Gmail account(s). Inbox zero!";
        }
        out.append("--- Scanned ").append(tokens.size()).append(" account(s), ")
           .append(totalShown).append(" have unread mail ---");
        return out.toString().trim();
    }

    @Tool(description = "Get the unread email count across ALL connected Gmail accounts, broken down per account.")
    public String getUnreadCountAllAccounts() {
        notifier.notify("Counting unread across all Gmail accounts...");
        java.util.Map<String, String> tokens = oauthService.getAllValidAccessTokens("gmail");
        if (tokens.isEmpty()) return "No Gmail accounts connected.";

        StringBuilder sb = new StringBuilder();
        int grandTotal = 0;
        for (java.util.Map.Entry<String, String> e : tokens.entrySet()) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(GMAIL_API + "/labels/UNREAD"))
                        .header("Authorization", "Bearer " + e.getValue())
                        .GET().build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                int count = 0;
                if (resp.statusCode() == 200) {
                    JsonNode root = mapper.readTree(resp.body());
                    count = root.has("messagesTotal") ? root.get("messagesTotal").asInt() : 0;
                }
                grandTotal += count;
                sb.append("  ").append(e.getKey()).append(": ").append(count).append(" unread\n");
            } catch (Exception ex) {
                sb.append("  ").append(e.getKey()).append(": error (").append(ex.getMessage()).append(")\n");
            }
        }
        sb.insert(0, "Unread emails across " + tokens.size() + " account(s) — total: " + grandTotal + "\n\n");
        return sb.toString().trim();
    }

    /** Shared implementation used by per-account and aggregate tools. */
    private String fetchUnreadWithToken(String accessToken, int max, String accountLabel) {
        try {
            String listUrl = GMAIL_API + "/messages"
                    + "?q=" + URLEncoder.encode("is:unread", StandardCharsets.UTF_8)
                    + "&maxResults=" + max;
            HttpResponse<String> listResp = httpClient.send(HttpRequest.newBuilder()
                    .uri(URI.create(listUrl))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            if (listResp.statusCode() != 200) {
                return "Error fetching unread for " + accountLabel + ": HTTP " + listResp.statusCode();
            }
            JsonNode listRoot = mapper.readTree(listResp.body());
            JsonNode messages = listRoot.get("messages");
            int resultSize = listRoot.has("resultSizeEstimate") ? listRoot.get("resultSizeEstimate").asInt() : 0;
            if (messages == null || !messages.isArray() || messages.isEmpty()) {
                return "No unread emails for " + accountLabel + ".";
            }
            StringBuilder sb = new StringBuilder();
            sb.append(Math.min(messages.size(), max)).append(" of ~").append(resultSize).append(" unread:\n\n");
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
            return "Error fetching unread for " + accountLabel + ": " + e.getMessage();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════

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

            return "ID: " + messageId
                    + "\n   From: " + from
                    + "\n   Subject: " + subject
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

    /**
     * Build a diagnostic error message from a non-2xx Gmail API response.
     * Includes the status code, the message Google returned, and a hint based on
     * the most common failures (401 invalid creds, 403 missing scope, 429 rate-limited).
     */
    private String formatApiError(String op, HttpResponse<String> resp) {
        int code = resp.statusCode();
        String body = resp.body() == null ? "" : resp.body();
        String googleMessage = extractGoogleErrorMessage(body);
        String googleStatus = extractGoogleErrorField(body, "status");
        String googleReason = extractGoogleErrorField(body, "reason");

        String hint;
        if (code == 401) {
            hint = "Token is invalid or revoked. Disconnect and re-connect Gmail in the Integrations tab.";
        } else if (code == 403) {
            // Google 403s come in several flavors — pick the right hint based on reason.
            String reasonLower = googleReason.toLowerCase(Locale.ROOT);
            String msgLower = googleMessage.toLowerCase(Locale.ROOT);
            String activationUrl = extractActivationUrl(body);
            if (reasonLower.contains("accessnotconfigured") || reasonLower.contains("service_disabled")
                    || msgLower.contains("has not been used in project")
                    || msgLower.contains("it is disabled")) {
                hint = "The Gmail API is NOT enabled in your Google Cloud project. "
                     + "Open " + (activationUrl.isBlank()
                            ? "https://console.developers.google.com/apis/library/gmail.googleapis.com"
                            : activationUrl)
                     + " → click Enable → wait ~2 minutes → retry. This is a one-time project setup, "
                     + "not an auth problem (your token is fine).";
            } else if (reasonLower.contains("ratelimitexceeded") || reasonLower.contains("userratelimit")) {
                hint = "Hit a Gmail quota. Wait a few minutes and retry.";
            } else if (reasonLower.contains("insufficientpermissions")
                    || msgLower.contains("insufficient authentication scopes")
                    || msgLower.contains("request had insufficient")) {
                hint = "Token is missing a required scope. Disconnect Gmail, then reconnect and ensure "
                     + "you grant BOTH 'Read your emails' and 'Send email' on the Google consent screen "
                     + "(don't uncheck any boxes).";
            } else {
                hint = "403 from Google. See the message above for specifics.";
            }
        } else if (code == 429) {
            hint = "Hit Gmail rate limit. Wait a minute and retry.";
        } else if (code >= 500) {
            hint = "Google server error. Retry shortly.";
        } else {
            hint = "Unexpected response. See log for full body.";
        }

        log.warn("[GmailApi] {} → HTTP {} {} {} body={}", op, code, googleStatus, googleReason,
                body.length() > 500 ? body.substring(0, 500) + "..." : body);

        StringBuilder sb = new StringBuilder();
        sb.append("Gmail API ").append(op).append(" failed: HTTP ").append(code);
        if (!googleStatus.isBlank()) sb.append(" (").append(googleStatus).append(")");
        sb.append(".\n");
        if (!googleMessage.isBlank()) sb.append("Google says: ").append(googleMessage).append("\n");
        sb.append("Hint: ").append(hint);
        return sb.toString();
    }

    private String extractGoogleErrorMessage(String body) {
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode err = root.get("error");
            if (err == null) return "";
            JsonNode msg = err.get("message");
            return msg != null ? msg.asText("") : "";
        } catch (Exception e) {
            return "";
        }
    }

    private String extractGoogleErrorField(String body, String field) {
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode err = root.get("error");
            if (err == null) return "";
            JsonNode v = err.get(field);
            if (v != null) return v.asText("");
            JsonNode errors = err.get("errors");
            if (errors != null && errors.isArray() && errors.size() > 0) {
                JsonNode f = errors.get(0).get(field);
                if (f != null) return f.asText("");
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Pulls the activation URL out of a SERVICE_DISABLED error body, which looks like:
     * {@code error.details[].metadata.activationUrl}. Returns empty string if absent.
     */
    private String extractActivationUrl(String body) {
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode details = root.path("error").path("details");
            if (details.isArray()) {
                for (JsonNode d : details) {
                    JsonNode md = d.path("metadata");
                    JsonNode url = md.get("activationUrl");
                    if (url != null && !url.asText("").isBlank()) return url.asText();
                }
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }
}
