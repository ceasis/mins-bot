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

/**
 * Google Drive tools — list, search, and read files from Drive.
 * Requires the "drive" integration to be connected in the Integrations tab
 * (grants the {@code drive.readonly} scope).
 */
@Component
public class DriveTools {

    private static final Logger log = LoggerFactory.getLogger(DriveTools.class);
    private static final String API = "https://www.googleapis.com/drive/v3";

    private final GoogleIntegrationOAuthService oauth;
    private final ToolExecutionNotifier notifier;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    public DriveTools(GoogleIntegrationOAuthService oauth, ToolExecutionNotifier notifier) {
        this.oauth = oauth;
        this.notifier = notifier;
    }

    @Tool(description = "List recent files from Google Drive. Returns the most recently modified files "
            + "with name, type, modified date, and file ID. Use when the user asks 'what's in my Drive', "
            + "'recent Google Drive files', or 'show my Drive'.")
    @com.minsbot.offline.RequiresOnline("Google Drive — list files")
    public String listDriveFiles(
            @ToolParam(description = "Max files to return (1-50)") double maxResults) {
        int max = Math.max(1, Math.min(50, (int) Math.round(maxResults)));
        notifier.notify("Listing recent Drive files...");

        String token = oauth.getValidAccessToken("drive");
        if (token == null) return "Drive not connected. Connect Google Drive in the Integrations tab.";

        try {
            String url = API + "/files?pageSize=" + max
                    + "&orderBy=modifiedTime desc"
                    + "&fields=" + URLEncoder.encode("files(id,name,mimeType,modifiedTime,size,owners,webViewLink)", StandardCharsets.UTF_8);
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return "Drive API error (HTTP " + resp.statusCode() + "): " + resp.body();
            }
            return formatFileList(resp.body(), "Recent Drive files");
        } catch (Exception e) {
            log.error("[Drive] list failed: {}", e.getMessage(), e);
            return "Failed to list Drive files: " + e.getMessage();
        }
    }

    @Tool(description = "Search Google Drive with a query. Supports Drive search syntax: "
            + "'name contains \"report\"', 'mimeType = \"application/pdf\"', 'modifiedTime > \"2026-01-01\"', "
            + "'fullText contains \"budget\"'. Simple strings like 'budget 2026' are auto-converted to a "
            + "fullText + name search. Use when the user asks to find a specific file.")
    public String searchDriveFiles(
            @ToolParam(description = "Search query — simple text or Drive query syntax") String query,
            @ToolParam(description = "Max results to return (1-30)") double maxResults) {
        if (query == null || query.isBlank()) return "Query is required.";
        int max = Math.max(1, Math.min(30, (int) Math.round(maxResults)));
        notifier.notify("Searching Drive: " + query);

        String token = oauth.getValidAccessToken("drive");
        if (token == null) return "Drive not connected.";

        // If the query looks like plain text (no Drive operators), wrap it as fullText + name
        String q = query.trim();
        boolean looksLikeDriveQuery = q.matches(".*(=|!=|<|>|contains|and|or|in parents|trashed).*");
        if (!looksLikeDriveQuery) {
            String safe = q.replace("\\", "\\\\").replace("'", "\\'");
            q = "(fullText contains '" + safe + "' or name contains '" + safe + "') and trashed = false";
        }

        try {
            String url = API + "/files?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8)
                    + "&pageSize=" + max
                    + "&orderBy=modifiedTime desc"
                    + "&fields=" + URLEncoder.encode("files(id,name,mimeType,modifiedTime,size,owners,webViewLink)", StandardCharsets.UTF_8);
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return "Drive API error (HTTP " + resp.statusCode() + "): " + resp.body();
            }
            return formatFileList(resp.body(), "Drive search: " + query);
        } catch (Exception e) {
            log.error("[Drive] search failed: {}", e.getMessage(), e);
            return "Failed to search Drive: " + e.getMessage();
        }
    }

    @Tool(description = "Read the text content of a Google Drive file by its ID. "
            + "Google Docs → exported as plain text. Google Sheets → exported as CSV. "
            + "Google Slides → exported as plain text. Plain text files (.txt, .md, .csv) → raw content. "
            + "PDFs and binary files are not supported by this tool (returns file metadata only). "
            + "Get the file ID from listDriveFiles or searchDriveFiles. "
            + "Content is capped at ~20,000 chars to keep replies manageable.")
    public String readDriveFile(
            @ToolParam(description = "Drive file ID (from listDriveFiles/searchDriveFiles)") String fileId) {
        if (fileId == null || fileId.isBlank()) return "File ID is required.";
        notifier.notify("Reading Drive file " + fileId + "...");

        String token = oauth.getValidAccessToken("drive");
        if (token == null) return "Drive not connected.";

        try {
            // Fetch metadata first to figure out the MIME type
            String metaUrl = API + "/files/" + fileId
                    + "?fields=" + URLEncoder.encode("id,name,mimeType,size,modifiedTime,webViewLink", StandardCharsets.UTF_8);
            HttpResponse<String> metaResp = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(metaUrl))
                    .header("Authorization", "Bearer " + token)
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            if (metaResp.statusCode() != 200) {
                return "Drive API error fetching metadata (HTTP " + metaResp.statusCode() + "): " + metaResp.body();
            }
            JsonNode meta = mapper.readTree(metaResp.body());
            String name = meta.path("name").asText("?");
            String mimeType = meta.path("mimeType").asText("");
            String modified = formatIsoDate(meta.path("modifiedTime").asText(""));
            String link = meta.path("webViewLink").asText("");

            String contentUrl;
            boolean isExport = false;
            switch (mimeType) {
                case "application/vnd.google-apps.document" -> {
                    contentUrl = API + "/files/" + fileId + "/export?mimeType=text/plain";
                    isExport = true;
                }
                case "application/vnd.google-apps.spreadsheet" -> {
                    contentUrl = API + "/files/" + fileId + "/export?mimeType=text/csv";
                    isExport = true;
                }
                case "application/vnd.google-apps.presentation" -> {
                    contentUrl = API + "/files/" + fileId + "/export?mimeType=text/plain";
                    isExport = true;
                }
                default -> contentUrl = API + "/files/" + fileId + "?alt=media";
            }

            // Skip download for binary types
            if (!isExport && isBinaryMime(mimeType)) {
                StringBuilder sb = new StringBuilder();
                sb.append("Name: ").append(name).append("\n");
                sb.append("Type: ").append(mimeType).append("\n");
                sb.append("Modified: ").append(modified).append("\n");
                if (!link.isBlank()) sb.append("Link: ").append(link).append("\n");
                sb.append("\n(Binary file — content can't be read as text. Open the link above to view.)");
                return sb.toString();
            }

            HttpResponse<String> contentResp = http.send(HttpRequest.newBuilder()
                    .uri(URI.create(contentUrl))
                    .header("Authorization", "Bearer " + token)
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            if (contentResp.statusCode() != 200) {
                return "Drive API error fetching content (HTTP " + contentResp.statusCode() + "): " + contentResp.body();
            }

            String body = contentResp.body();
            StringBuilder out = new StringBuilder();
            out.append("Name: ").append(name).append("\n");
            out.append("Type: ").append(mimeType).append("\n");
            out.append("Modified: ").append(modified).append("\n");
            if (!link.isBlank()) out.append("Link: ").append(link).append("\n");
            out.append("\n--- CONTENT ---\n\n");
            if (body.length() > 20000) {
                out.append(body, 0, 20000).append("\n\n[...truncated — file is ").append(body.length()).append(" chars total]");
            } else {
                out.append(body);
            }
            return out.toString();
        } catch (Exception e) {
            log.error("[Drive] read failed: {}", e.getMessage(), e);
            return "Failed to read Drive file: " + e.getMessage();
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private String formatFileList(String body, String header) throws Exception {
        JsonNode root = mapper.readTree(body);
        JsonNode files = root.get("files");
        if (files == null || !files.isArray() || files.isEmpty()) {
            return "No files found.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(header).append(" (").append(files.size()).append(" result").append(files.size() == 1 ? "" : "s").append("):\n\n");
        int i = 0;
        for (JsonNode f : files) {
            i++;
            String id = f.path("id").asText();
            String name = f.path("name").asText("?");
            String mime = shortMimeType(f.path("mimeType").asText(""));
            String modified = formatIsoDate(f.path("modifiedTime").asText(""));
            String size = f.has("size") ? formatBytes(f.path("size").asLong()) : "";
            sb.append(i).append(". ").append(name).append("\n");
            sb.append("   Type: ").append(mime);
            if (!size.isBlank()) sb.append(" · ").append(size);
            sb.append(" · Modified: ").append(modified).append("\n");
            sb.append("   ID: ").append(id).append("\n\n");
        }
        sb.append("To read a file's content, call readDriveFile(<id>).");
        return sb.toString();
    }

    private static String shortMimeType(String mime) {
        if (mime == null) return "";
        return switch (mime) {
            case "application/vnd.google-apps.document" -> "Google Doc";
            case "application/vnd.google-apps.spreadsheet" -> "Google Sheet";
            case "application/vnd.google-apps.presentation" -> "Google Slides";
            case "application/vnd.google-apps.folder" -> "Folder";
            case "application/vnd.google-apps.form" -> "Google Form";
            case "application/pdf" -> "PDF";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "Word (.docx)";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "Excel (.xlsx)";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "PowerPoint (.pptx)";
            case "text/plain" -> "Text";
            case "text/csv" -> "CSV";
            case "image/jpeg", "image/png", "image/gif", "image/webp" -> "Image";
            default -> mime;
        };
    }

    private static boolean isBinaryMime(String mime) {
        if (mime == null) return false;
        if (mime.startsWith("text/")) return false;
        if (mime.startsWith("application/vnd.google-apps")) return false;
        return !mime.equals("application/json")
                && !mime.equals("application/xml")
                && !mime.equals("application/javascript");
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static String formatIsoDate(String iso) {
        try {
            return java.time.OffsetDateTime.parse(iso)
                    .atZoneSameInstant(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("MMM d, h:mm a"));
        } catch (Exception e) {
            return iso;
        }
    }
}
