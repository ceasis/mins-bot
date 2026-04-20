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

    // ═══ Write tools (create / update / delete events) ════════════════════

    @Tool(description = "Create a new event in Google Calendar. Use when the user says "
            + "'add a meeting tomorrow at 3pm', 'schedule lunch with Alice Thursday noon', "
            + "'book an appointment for X'. Pass times in ISO 8601 with timezone "
            + "(e.g. '2026-04-22T15:00:00+08:00') OR pass a YYYY-MM-DD date for an all-day event "
            + "(set endDateTime to the same date for single-day, or one day later for a range).")
    public String createCalendarEvent(
            @ToolParam(description = "Event title / summary") String title,
            @ToolParam(description = "Start — ISO datetime 'YYYY-MM-DDTHH:mm:ss±HH:mm' for timed events, or 'YYYY-MM-DD' for all-day") String startDateTime,
            @ToolParam(description = "End — same format as start. For all-day events pass the next day's date.") String endDateTime,
            @ToolParam(description = "Optional location, e.g. 'Office' or '123 Main St'. Use '-' if none.") String location,
            @ToolParam(description = "Optional description. Use '-' if none.") String description,
            @ToolParam(description = "Optional comma-separated attendee emails. Use '-' if none.") String attendeesCsv) {

        if (title == null || title.isBlank()) return "Event title is required.";
        if (startDateTime == null || startDateTime.isBlank()) return "startDateTime is required.";
        if (endDateTime == null || endDateTime.isBlank()) return "endDateTime is required.";
        notifier.notify("Creating calendar event: " + title);

        String token = oauthService.getValidAccessToken("calendar");
        if (token == null) return "Google Calendar not connected.";

        boolean allDay = startDateTime.length() == 10 && !startDateTime.contains("T");

        StringBuilder json = new StringBuilder("{");
        json.append("\"summary\":\"").append(escape(title)).append("\"");
        if (location != null && !location.isBlank() && !location.equals("-")) {
            json.append(",\"location\":\"").append(escape(location)).append("\"");
        }
        if (description != null && !description.isBlank() && !description.equals("-")) {
            json.append(",\"description\":\"").append(escape(description)).append("\"");
        }
        if (allDay) {
            json.append(",\"start\":{\"date\":\"").append(startDateTime).append("\"}");
            json.append(",\"end\":{\"date\":\"").append(endDateTime).append("\"}");
        } else {
            json.append(",\"start\":{\"dateTime\":\"").append(startDateTime).append("\"}");
            json.append(",\"end\":{\"dateTime\":\"").append(endDateTime).append("\"}");
        }
        if (attendeesCsv != null && !attendeesCsv.isBlank() && !attendeesCsv.equals("-")) {
            json.append(",\"attendees\":[");
            String[] emails = attendeesCsv.split(",");
            for (int i = 0; i < emails.length; i++) {
                if (i > 0) json.append(",");
                json.append("{\"email\":\"").append(escape(emails[i].trim())).append("\"}");
            }
            json.append("]");
        }
        json.append("}");

        try {
            HttpResponse<String> resp = httpClient.send(HttpRequest.newBuilder()
                    .uri(URI.create(CALENDAR_API + "/calendars/primary/events"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.toString()))
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return "Calendar API error (HTTP " + resp.statusCode() + "): " + resp.body();
            }
            JsonNode root = mapper.readTree(resp.body());
            String id = root.path("id").asText("?");
            String link = root.path("htmlLink").asText("");
            return "Created event '" + title + "' (id: " + id + ")"
                    + (link.isBlank() ? "" : "\nLink: " + link);
        } catch (Exception e) {
            log.error("[Calendar] create failed: {}", e.getMessage(), e);
            return "Failed to create event: " + e.getMessage();
        }
    }

    @Tool(description = "Delete a Google Calendar event by its ID. "
            + "Use when the user says 'cancel that meeting', 'delete the 3pm event', etc. "
            + "Get the event ID from getTodayEvents/getEventsForDate/getUpcomingEvents.")
    public String deleteCalendarEvent(
            @ToolParam(description = "Event ID to delete") String eventId) {
        if (eventId == null || eventId.isBlank()) return "eventId is required.";
        notifier.notify("Deleting calendar event " + eventId + "...");

        String token = oauthService.getValidAccessToken("calendar");
        if (token == null) return "Google Calendar not connected.";

        try {
            HttpResponse<String> resp = httpClient.send(HttpRequest.newBuilder()
                    .uri(URI.create(CALENDAR_API + "/calendars/primary/events/" + eventId))
                    .header("Authorization", "Bearer " + token)
                    .DELETE().build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 204 || resp.statusCode() == 200) {
                return "Deleted event " + eventId;
            }
            return "Calendar API error (HTTP " + resp.statusCode() + "): " + resp.body();
        } catch (Exception e) {
            log.error("[Calendar] delete failed: {}", e.getMessage(), e);
            return "Failed to delete event: " + e.getMessage();
        }
    }

    @Tool(description = "Quick-add a Calendar event using natural language. Google parses the text "
            + "('Dinner with Alice at Nobu 7pm tomorrow', 'Team standup every weekday 9:30am for 30 min'). "
            + "Simpler than createCalendarEvent when the user's phrasing is already clear.")
    public String quickAddCalendarEvent(
            @ToolParam(description = "Natural-language event description") String text) {
        if (text == null || text.isBlank()) return "Text is required.";
        notifier.notify("Quick-adding event: " + text);

        String token = oauthService.getValidAccessToken("calendar");
        if (token == null) return "Google Calendar not connected.";

        try {
            String url = CALENDAR_API + "/calendars/primary/events/quickAdd"
                    + "?text=" + java.net.URLEncoder.encode(text, StandardCharsets.UTF_8);
            HttpResponse<String> resp = httpClient.send(HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return "Calendar API error (HTTP " + resp.statusCode() + "): " + resp.body();
            }
            JsonNode root = mapper.readTree(resp.body());
            String id = root.path("id").asText("?");
            String summary = root.path("summary").asText(text);
            String link = root.path("htmlLink").asText("");
            String startStr = formatEventTime(root.path("start"));
            return "Added event '" + summary + "' (id: " + id + ")"
                    + (startStr.equals("?") ? "" : " — starts " + startStr)
                    + (link.isBlank() ? "" : "\nLink: " + link);
        } catch (Exception e) {
            log.error("[Calendar] quickAdd failed: {}", e.getMessage(), e);
            return "Failed to quick-add event: " + e.getMessage();
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
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
