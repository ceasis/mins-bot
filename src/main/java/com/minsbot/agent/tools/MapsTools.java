package com.minsbot.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Google Maps integration — open places, directions, and nearby searches in the
 * user's default browser using Google's documented Maps URLs API.
 * See: https://developers.google.com/maps/documentation/urls/get-started
 */
@Component
public class MapsTools {

    private static final Logger log = LoggerFactory.getLogger(MapsTools.class);

    private final ToolExecutionNotifier notifier;

    public MapsTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @Tool(description = "Open Google Maps to a specific place, address, landmark, or business in the default browser. "
            + "USE THIS whenever the user says 'open maps to X', 'show me X on a map', 'where is X', "
            + "'find X on Google Maps', 'take me to X on the map', or mentions a specific location they want to see. "
            + "Works for addresses, place names, businesses, landmarks, and coordinates.")
    public String openMapsPlace(
            @ToolParam(description = "Place name, address, business, or coordinates — e.g. 'Eiffel Tower', '1600 Pennsylvania Ave', 'SM Mall of Asia', '14.5995,120.9842'") String query) {
        if (query == null || query.isBlank()) {
            return "Please provide a place name, address, or landmark.";
        }
        notifier.notify("Opening Google Maps for: " + query);
        String url = "https://www.google.com/maps/search/?api=1&query="
                + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
        return openUrl(url, "Opened Google Maps for: " + query);
    }

    @Tool(description = "Open Google Maps directions from one place to another in the default browser. "
            + "Use when the user asks 'how do I get from X to Y', 'directions from X to Y', 'route to X', "
            + "'navigate to X'. If the origin is blank, Maps uses the user's current location.")
    public String openMapsDirections(
            @ToolParam(description = "Starting point (leave blank for current location)") String origin,
            @ToolParam(description = "Destination address, place, or landmark") String destination,
            @ToolParam(description = "Travel mode: driving, walking, bicycling, transit, or two-wheeler (optional)") String travelMode) {
        if (destination == null || destination.isBlank()) {
            return "Please provide a destination.";
        }
        notifier.notify("Opening Google Maps directions to: " + destination);
        StringBuilder url = new StringBuilder("https://www.google.com/maps/dir/?api=1");
        url.append("&destination=").append(URLEncoder.encode(destination.trim(), StandardCharsets.UTF_8));
        if (origin != null && !origin.isBlank()) {
            url.append("&origin=").append(URLEncoder.encode(origin.trim(), StandardCharsets.UTF_8));
        }
        if (travelMode != null && !travelMode.isBlank()) {
            String mode = travelMode.trim().toLowerCase();
            if (mode.equals("driving") || mode.equals("walking") || mode.equals("bicycling")
                    || mode.equals("transit") || mode.equals("two-wheeler")) {
                url.append("&travelmode=").append(mode);
            }
        }
        return openUrl(url.toString(), "Opened directions to " + destination
                + (origin != null && !origin.isBlank() ? " from " + origin : " from your current location"));
    }

    @Tool(description = "Search Google Maps for places of a given type near a location. Use for queries like "
            + "'find coffee shops near me', 'restaurants in Manila', 'gas stations near the airport', 'pharmacies near X'. "
            + "Opens the Maps results in the default browser.")
    public String findNearby(
            @ToolParam(description = "What to search for — e.g. 'coffee shops', 'restaurants', 'gas stations', 'pharmacy'") String placeType,
            @ToolParam(description = "Location — address, neighborhood, city, or 'near me' (optional)") String location) {
        if (placeType == null || placeType.isBlank()) {
            return "Please specify what to search for (e.g. 'coffee shops').";
        }
        String q = placeType.trim();
        if (location != null && !location.isBlank() && !location.equalsIgnoreCase("near me")) {
            q = placeType.trim() + " near " + location.trim();
        }
        notifier.notify("Searching Maps for: " + q);
        String url = "https://www.google.com/maps/search/?api=1&query="
                + URLEncoder.encode(q, StandardCharsets.UTF_8);
        return openUrl(url, "Searched Google Maps: " + q);
    }

    @Tool(description = "Open Google Maps at specific latitude/longitude coordinates. Use when the user "
            + "has GPS coordinates or wants to see an exact spot on the map.")
    public String openMapsCoordinates(
            @ToolParam(description = "Latitude, e.g. 14.5995") double latitude,
            @ToolParam(description = "Longitude, e.g. 120.9842") double longitude,
            @ToolParam(description = "Zoom level 1-21 (default 15)") Integer zoom) {
        int z = zoom != null ? Math.max(1, Math.min(21, zoom)) : 15;
        notifier.notify("Opening Maps at " + latitude + "," + longitude);
        // @lat,lng,zoomz — Google's supported coordinate URL format
        String url = "https://www.google.com/maps/@" + latitude + "," + longitude + "," + z + "z";
        return openUrl(url, "Opened Maps at " + latitude + ", " + longitude);
    }

    // ─── Internals ───

    private String openUrl(String url, String successMessage) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
                return successMessage;
            }
            new ProcessBuilder("cmd", "/c", "start", "", url)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return successMessage;
        } catch (Exception e) {
            log.warn("[Maps] Failed to open URL: {}", e.getMessage());
            return "Failed to open Maps: " + e.getMessage();
        }
    }
}
