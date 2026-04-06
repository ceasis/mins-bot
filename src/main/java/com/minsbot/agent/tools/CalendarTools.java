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
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Google Calendar tools — fetch events via the Google Calendar API.
 * Requires the "calendar" integration to be connected in the Integrations tab.
 */
@Component
public class CalendarTools {

    private static final Logger log = LoggerFactory.getLogger(CalendarTools.class);
    private static final String CALENDAR_API = "https://www.googleapis.com/calendar/v3";

    private final GoogleIntegrationOAuthService oauthService;
    private final ToolExecutionNotifier notifier;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public CalendarTools(GoogleIntegrationOAuthService oauthService,
                         ToolExecutionNotifier notifier) {
        this.oauthService = oauthService;
        this.notifier = notifier;
    }

    @Tool(description = "Get today's events from Google Calendar. Returns a list of events with time, title, " +
            "and location. Use this for morning briefings, scheduling checks, or when the user asks 'what's on my calendar today'. " +
            "Requires Google Calendar integration to be connected in the Integrations tab.")
    public String getTodayEvents() {
        return getEventsForDate(LocalDate.now().toString());
    }

    @Tool(description = "Get events from Google Calendar for a specific date (YYYY-MM-DD format). " +
            "Returns a list of events with time, title, location, and description.")
    public String getEventsForDate(
            @ToolParam(description = "Date in YYYY-MM-DD format, e.g. '2026-04-06'") String date) {
        notifier.notify("Checking Google Calendar for " + date + "...");

        String accessToken = oauthService.getValidAccessToken("calendar");
        if (accessToken == null) {
            return "Google Calendar not connected. Please connect it in the Integrations tab.";
        }

        try {
            LocalDate targetDate = LocalDate.parse(date);
            ZoneId zone = ZoneId.systemDefault();
            String timeMin = targetDate.atStartOfDay(zone).toInstant().toString();
            String timeMax = targetDate.plusDays(1).atStartOfDay(zone).toInstant().toString();

            String url = CALENDAR_API + "/calendars/primary/events"
                    + "?timeMin=" + URLEncoder.encode(timeMin, StandardCharsets.UTF_8)
                    + "&timeMax=" + URLEncoder.encode(timeMax, StandardCharsets.UTF_8)
                    + "&singleEvents=true&orderBy=startTime&maxResults=50";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("[Calendar] API returned {}: {}", response.statusCode(), response.body());
                return "Calendar API error (HTTP " + response.statusCode() + "). Try reconnecting in Integrations tab.";
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode items = root.get("items");
            if (items == null || !items.isArray() || items.isEmpty()) {
                return "No events found for " + date + ".";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Events for ").append(date).append(" (").append(targetDate.getDayOfWeek()).append("):\n\n");

            int count = 0;
            for (JsonNode event : items) {
                count++;
                String summary = event.has("summary") ? event.get("summary").asText() : "(No title)";
                String location = event.has("location") ? event.get("location").asText() : null;
                String description = event.has("description") ? event.get("description").asText() : null;

                // Parse start/end times
                String startStr = formatEventTime(event.get("start"));
                String endStr = formatEventTime(event.get("end"));

                sb.append(count).append(". ").append(summary).append("\n");
                sb.append("   Time: ").append(startStr);
                if (endStr != null) sb.append(" - ").append(endStr);
                sb.append("\n");
                if (location != null) sb.append("   Location: ").append(location).append("\n");
                if (description != null && description.length() <= 200) {
                    sb.append("   Note: ").append(description.replace("\n", " ").trim()).append("\n");
                }
                sb.append("\n");
            }

            sb.append("Total: ").append(count).append(" event").append(count != 1 ? "s" : "");
            return sb.toString();

        } catch (Exception e) {
            log.error("[Calendar] Error fetching events: {}", e.getMessage(), e);
            return "Failed to fetch calendar events: " + e.getMessage();
        }
    }

    @Tool(description = "Get upcoming events from Google Calendar for the next N days. " +
            "Use for weekly planning or when user asks 'what's coming up this week'.")
    public String getUpcomingEvents(
            @ToolParam(description = "Number of days to look ahead (1-14)") double daysAhead) {
        int days = Math.max(1, Math.min(14, (int) Math.round(daysAhead)));
        notifier.notify("Checking calendar for next " + days + " days...");

        String accessToken = oauthService.getValidAccessToken("calendar");
        if (accessToken == null) {
            return "Google Calendar not connected. Please connect it in the Integrations tab.";
        }

        try {
            ZoneId zone = ZoneId.systemDefault();
            LocalDate today = LocalDate.now();
            String timeMin = today.atStartOfDay(zone).toInstant().toString();
            String timeMax = today.plusDays(days).atStartOfDay(zone).toInstant().toString();

            String url = CALENDAR_API + "/calendars/primary/events"
                    + "?timeMin=" + URLEncoder.encode(timeMin, StandardCharsets.UTF_8)
                    + "&timeMax=" + URLEncoder.encode(timeMax, StandardCharsets.UTF_8)
                    + "&singleEvents=true&orderBy=startTime&maxResults=100";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return "Calendar API error (HTTP " + response.statusCode() + ").";
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode items = root.get("items");
            if (items == null || !items.isArray() || items.isEmpty()) {
                return "No events in the next " + days + " days.";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Upcoming events (next ").append(days).append(" days):\n\n");

            String currentDate = "";
            int count = 0;
            for (JsonNode event : items) {
                count++;
                String summary = event.has("summary") ? event.get("summary").asText() : "(No title)";
                String eventDate = extractDate(event.get("start"));

                if (!eventDate.equals(currentDate)) {
                    currentDate = eventDate;
                    try {
                        LocalDate d = LocalDate.parse(eventDate);
                        sb.append("--- ").append(eventDate).append(" (").append(d.getDayOfWeek()).append(") ---\n");
                    } catch (Exception e) {
                        sb.append("--- ").append(eventDate).append(" ---\n");
                    }
                }

                String startStr = formatEventTime(event.get("start"));
                sb.append("  ").append(startStr).append(" — ").append(summary).append("\n");
            }

            sb.append("\nTotal: ").append(count).append(" event").append(count != 1 ? "s" : "");
            return sb.toString();

        } catch (Exception e) {
            log.error("[Calendar] Error fetching upcoming events: {}", e.getMessage(), e);
            return "Failed to fetch upcoming events: " + e.getMessage();
        }
    }

    private String formatEventTime(JsonNode timeNode) {
        if (timeNode == null) return "?";
        if (timeNode.has("dateTime")) {
            try {
                OffsetDateTime odt = OffsetDateTime.parse(timeNode.get("dateTime").asText());
                return odt.toLocalTime().format(DateTimeFormatter.ofPattern("h:mm a"));
            } catch (Exception e) {
                return timeNode.get("dateTime").asText();
            }
        }
        if (timeNode.has("date")) {
            return "All day";
        }
        return "?";
    }

    private String extractDate(JsonNode timeNode) {
        if (timeNode == null) return "";
        if (timeNode.has("dateTime")) {
            try {
                return OffsetDateTime.parse(timeNode.get("dateTime").asText()).toLocalDate().toString();
            } catch (Exception e) {
                return "";
            }
        }
        if (timeNode.has("date")) {
            return timeNode.get("date").asText();
        }
        return "";
    }
}
