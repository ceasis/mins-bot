package com.botsfer.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Weather via Open-Meteo (free, no API key).
 * Geocodes place names and returns current conditions.
 */
@Component
public class WeatherTools {

    private static final String GEOCODING = "https://geocoding-api.open-meteo.com/v1/search";
    private static final String FORECAST = "https://api.open-meteo.com/v1/forecast";

    private static final ObjectMapper OM = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final ToolExecutionNotifier notifier;

    public WeatherTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    @Tool(description = "Get current weather for a city or place (e.g. 'New York', 'London', 'Tokyo'). " +
            "Uses Open-Meteo; no API key required. Returns temperature, conditions, humidity, wind.")
    public String getWeather(
            @ToolParam(description = "City or place name, e.g. 'Paris', 'Berlin'") String location) {
        if (location == null || location.isBlank()) {
            return "Please provide a location (e.g. city name).";
        }
        notifier.notify("Getting weather for: " + location);
        try {
            double lat = Double.NaN;
            double lon = 0;
            String placeName = location.trim();
            // If user passed "lat,lon" (e.g. "40.71,-74.01"), use directly
            String[] parts = location.trim().split("\\s*,\\s*");
            if (parts.length == 2) {
                try {
                    lat = Double.parseDouble(parts[0].trim());
                    lon = Double.parseDouble(parts[1].trim());
                } catch (NumberFormatException e) {
                    lat = Double.NaN;
                }
            }
            if (Double.isNaN(lat)) {
                JsonNode geo = geocode(placeName);
                if (geo == null || !geo.has("results") || !geo.get("results").isArray() || geo.get("results").isEmpty()) {
                    return "No location found for: " + placeName;
                }
                JsonNode first = geo.get("results").get(0);
                lat = first.path("latitude").asDouble();
                lon = first.path("longitude").asDouble();
                if (first.has("name")) placeName = first.get("name").asText();
                if (first.has("admin1") && !first.path("admin1").asText().isBlank()) {
                    placeName += ", " + first.path("admin1").asText();
                }
                if (first.has("country")) placeName += ", " + first.path("country").asText();
            }
            JsonNode forecast = fetchForecast(lat, lon);
            if (forecast == null || !forecast.has("current")) {
                return "Could not fetch weather for " + placeName;
            }
            JsonNode cur = forecast.get("current");
            double temp = cur.path("temperature_2m").asDouble();
            int humidity = cur.path("relative_humidity_2m").asInt(0);
            double windKmh = cur.path("wind_speed_10m").asDouble(0);
            int wmo = cur.path("weather_code").asInt(0);
            String conditions = wmoToText(wmo);
            String tz = forecast.path("timezone").asText("UTC");
            return String.format("%s — %s. Temperature %.1f°C, humidity %d%%, wind %.1f km/h. (Timezone: %s)",
                    placeName, conditions, temp, humidity, windKmh, tz);
        } catch (Exception e) {
            return "Weather error: " + e.getMessage();
        }
    }

    private JsonNode geocode(String name) throws Exception {
        String q = URLEncoder.encode(name, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(GEOCODING + "?name=" + q + "&count=5"))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() != 200) return null;
        return OM.readTree(res.body());
    }

    private JsonNode fetchForecast(double lat, double lon) throws Exception {
        String url = FORECAST + "?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m&timezone=auto";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() != 200) return null;
        return OM.readTree(res.body());
    }

    private static String wmoToText(int code) {
        if (code == 0) return "Clear sky";
        if (code <= 3) return "Partly cloudy";
        if (code == 45 || code == 48) return "Foggy";
        if (code >= 51 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Rain showers";
        if (code >= 85 && code <= 86) return "Snow showers";
        if (code >= 95 && code <= 99) return "Thunderstorm";
        return "Variable (" + code + ")";
    }
}
