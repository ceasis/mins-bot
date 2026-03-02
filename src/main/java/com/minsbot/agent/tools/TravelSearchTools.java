package com.minsbot.agent.tools;

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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Travel search tools — flights and hotels.
 *
 * <p>Strategy (per Gemini's advice):
 * <ol>
 *   <li>URL parameter manipulation — construct direct Google Flights/Hotels URLs
 *       to bypass fragile UI clicking</li>
 *   <li>Playwright (headless w/ stealth) to render JS-heavy results pages</li>
 *   <li>DuckDuckGo text search as fallback</li>
 * </ol>
 */
@Component
public class TravelSearchTools {

    private static final Logger log = LoggerFactory.getLogger(TravelSearchTools.class);

    private final ToolExecutionNotifier notifier;
    private final PlaywrightService pw;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public TravelSearchTools(ToolExecutionNotifier notifier, PlaywrightService pw) {
        this.notifier = notifier;
        this.pw = pw;
    }

    // ═══ Flight Search ═══

    @Tool(description = "Search for flights between two locations. Uses Google Flights URL parameters " +
            "to go directly to results. Returns flight options with prices, airlines, and durations. " +
            "Use this when the user asks to find, search, or research flights.")
    public String searchFlights(
            @ToolParam(description = "Origin city or airport code, e.g. 'Manila' or 'MNL'") String from,
            @ToolParam(description = "Destination city or airport code, e.g. 'Taipei' or 'TPE'") String to,
            @ToolParam(description = "Departure date in YYYY-MM-DD format, e.g. '2026-04-15'") String departDate,
            @ToolParam(description = "Return date in YYYY-MM-DD format, or 'one-way' for one-way trip") String returnDate) {

        notifier.notify("Searching flights: " + from + " → " + to);
        log.info("[TravelSearch] Flights: {} → {} on {}, return {}", from, to, departDate, returnDate);

        // Strategy 1: Google Flights via URL parameters + Playwright
        String result = tryGoogleFlights(from, to, departDate, returnDate);
        if (result != null && !result.isBlank()) return result;

        // Strategy 2: DuckDuckGo text search fallback
        result = tryDdgSearch("flights from " + from + " to " + to + " " + departDate);
        if (result != null && !result.isBlank()) return result;

        return "Could not find flight results. Try searching manually on Google Flights: " +
                buildGoogleFlightsUrl(from, to, departDate, returnDate);
    }

    @Tool(description = "Search for hotels in a location. Uses Google Hotels URL parameters " +
            "to go directly to results. Returns hotel options with prices and ratings. " +
            "Use this when the user asks to find, search, or research hotels or accommodations.")
    public String searchHotels(
            @ToolParam(description = "Location to search hotels in, e.g. 'Taipei Taiwan'") String location,
            @ToolParam(description = "Check-in date in YYYY-MM-DD format") String checkinDate,
            @ToolParam(description = "Check-out date in YYYY-MM-DD format") String checkoutDate) {

        notifier.notify("Searching hotels in: " + location);
        log.info("[TravelSearch] Hotels: {} from {} to {}", location, checkinDate, checkoutDate);

        // Strategy 1: Google Hotels via URL parameters + Playwright
        String result = tryGoogleHotels(location, checkinDate, checkoutDate);
        if (result != null && !result.isBlank()) return result;

        // Strategy 2: DuckDuckGo text search fallback
        result = tryDdgSearch("hotels in " + location + " " + checkinDate + " to " + checkoutDate);
        if (result != null && !result.isBlank()) return result;

        return "Could not find hotel results. Try searching manually: " +
                buildGoogleHotelsUrl(location, checkinDate, checkoutDate);
    }

    // ═══ Google Flights ═══

    private String tryGoogleFlights(String from, String to, String departDate, String returnDate) {
        try {
            String url = buildGoogleFlightsUrl(from, to, departDate, returnDate);
            log.info("[TravelSearch] Google Flights URL: {}", url);

            // Use Playwright (headless w/ stealth) to render JS-heavy page
            String text = pw.getPageText(url);
            if (text == null || text.length() < 100) {
                log.info("[TravelSearch] Playwright returned too little text, trying HTML approach");
                return tryGoogleFlightsHtml(from, to, departDate, returnDate);
            }

            String parsed = parseFlightResults(text, from, to, departDate);
            if (parsed != null && !parsed.isBlank()) return parsed;

            // If parsing didn't extract structured results, return raw text
            return "Google Flights results for " + from + " → " + to + " on " + departDate + ":\n\n" +
                    truncate(text, 6000);
        } catch (Exception e) {
            log.warn("[TravelSearch] Google Flights failed: {}", e.getMessage());
            return null;
        }
    }

    private String tryGoogleFlightsHtml(String from, String to, String departDate, String returnDate) {
        try {
            String url = buildGoogleFlightsUrl(from, to, departDate, returnDate);
            String html = pw.getPageHtml(url);
            if (html == null || html.length() < 200) return null;

            // Extract text from rendered HTML
            String text = stripHtml(html);
            if (text.length() < 100) return null;

            return "Google Flights results for " + from + " → " + to + " on " + departDate + ":\n\n" +
                    truncate(text, 6000);
        } catch (Exception e) {
            log.warn("[TravelSearch] Google Flights HTML failed: {}", e.getMessage());
            return null;
        }
    }

    private String buildGoogleFlightsUrl(String from, String to, String departDate, String returnDate) {
        String query = "Flights to " + to + " from " + from + " on " + departDate;
        if (returnDate != null && !returnDate.equalsIgnoreCase("one-way") && !returnDate.isBlank()) {
            query += " return " + returnDate;
        }
        return "https://www.google.com/travel/flights?q=" +
                URLEncoder.encode(query, StandardCharsets.UTF_8);
    }

    // ═══ Google Hotels ═══

    private String tryGoogleHotels(String location, String checkin, String checkout) {
        try {
            String url = buildGoogleHotelsUrl(location, checkin, checkout);
            log.info("[TravelSearch] Google Hotels URL: {}", url);

            String text = pw.getPageText(url);
            if (text == null || text.length() < 100) {
                log.info("[TravelSearch] Playwright returned too little text for hotels");
                return tryGoogleHotelsHtml(location, checkin, checkout);
            }

            String parsed = parseHotelResults(text, location);
            if (parsed != null && !parsed.isBlank()) return parsed;

            return "Google Hotels results for " + location + " (" + checkin + " to " + checkout + "):\n\n" +
                    truncate(text, 6000);
        } catch (Exception e) {
            log.warn("[TravelSearch] Google Hotels failed: {}", e.getMessage());
            return null;
        }
    }

    private String tryGoogleHotelsHtml(String location, String checkin, String checkout) {
        try {
            String url = buildGoogleHotelsUrl(location, checkin, checkout);
            String html = pw.getPageHtml(url);
            if (html == null || html.length() < 200) return null;

            String text = stripHtml(html);
            if (text.length() < 100) return null;

            return "Google Hotels results for " + location + " (" + checkin + " to " + checkout + "):\n\n" +
                    truncate(text, 6000);
        } catch (Exception e) {
            log.warn("[TravelSearch] Google Hotels HTML failed: {}", e.getMessage());
            return null;
        }
    }

    private String buildGoogleHotelsUrl(String location, String checkin, String checkout) {
        String query = "Hotels in " + location;
        return "https://www.google.com/travel/hotels?q=" +
                URLEncoder.encode(query, StandardCharsets.UTF_8) +
                "&dates=" + checkin + "," + checkout;
    }

    // ═══ DuckDuckGo fallback ═══

    private String tryDdgSearch(String query) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://html.duckduckgo.com/html/?q=" + encoded;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;

            return parseDdgResults(response.body(), query);
        } catch (Exception e) {
            log.warn("[TravelSearch] DDG fallback failed: {}", e.getMessage());
            return null;
        }
    }

    private String parseDdgResults(String html, String query) {
        List<String> results = new ArrayList<>();
        Pattern resultBlock = Pattern.compile(
                "<a[^>]+class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>.*?" +
                        "class=\"result__snippet\"[^>]*>(.*?)</",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

        Matcher m = resultBlock.matcher(html);
        while (m.find() && results.size() < 8) {
            String link = m.group(1).trim();
            String title = stripTags(m.group(2)).trim();
            String snippet = stripTags(m.group(3)).trim();

            if (link.contains("uddg=")) {
                try {
                    String decoded = java.net.URLDecoder.decode(
                            link.substring(link.indexOf("uddg=") + 5), StandardCharsets.UTF_8);
                    if (decoded.contains("&")) decoded = decoded.substring(0, decoded.indexOf("&"));
                    link = decoded;
                } catch (Exception ignored) {}
            }

            if (!title.isBlank() && !link.isBlank()) {
                results.add((results.size() + 1) + ". " + title + "\n   " +
                        (snippet.isBlank() ? "" : snippet + "\n   ") + link);
            }
        }

        if (results.isEmpty()) return null;
        return "Web search results for: " + query + "\n\n" + String.join("\n\n", results);
    }

    // ═══ Result parsing ═══

    private String parseFlightResults(String text, String from, String to, String departDate) {
        // Look for price patterns (₱, $, €, etc.) and airline names
        Pattern pricePattern = Pattern.compile("[₱$€£]\\s?[\\d,]+(?:\\.\\d{2})?");
        Pattern timePattern = Pattern.compile("\\d{1,2}:\\d{2}\\s*(?:AM|PM|am|pm)?\\s*[–-]\\s*\\d{1,2}:\\d{2}\\s*(?:AM|PM|am|pm)?");
        Pattern durationPattern = Pattern.compile("\\d+\\s*(?:hr?|hour)\\s*\\d*\\s*(?:min|m)?");

        Matcher priceMatcher = pricePattern.matcher(text);
        boolean hasFlightData = priceMatcher.find();

        if (!hasFlightData) return null; // No price data found — probably not a results page

        StringBuilder sb = new StringBuilder();
        sb.append("✈ Flights from ").append(from).append(" to ").append(to)
                .append(" on ").append(departDate).append("\n\n");

        // Split by common flight result delimiters and look for structured blocks
        String[] lines = text.split("\\n");
        int flightCount = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.length() < 5) continue;

            // Keep lines that contain prices, times, or airline-related keywords
            if (pricePattern.matcher(trimmed).find() ||
                    timePattern.matcher(trimmed).find() ||
                    durationPattern.matcher(trimmed).find() ||
                    trimmed.matches(".*(?:nonstop|1 stop|2 stops|Nonstop|Stop).*") ||
                    trimmed.matches(".*(?:Airlines?|Air |Philippine|Cebu|PAL|EVA|Cathay|ANA|Japan|Korean|Delta|United).*")) {
                sb.append(trimmed).append("\n");
                flightCount++;
            }
        }

        if (flightCount < 3) return null; // Not enough structured data
        return sb.toString().trim();
    }

    private String parseHotelResults(String text, String location) {
        Pattern pricePattern = Pattern.compile("[₱$€£]\\s?[\\d,]+(?:\\.\\d{2})?");
        Pattern ratingPattern = Pattern.compile("\\d\\.\\d\\s*(?:star|★|\\(\\d+\\))");

        Matcher priceMatcher = pricePattern.matcher(text);
        if (!priceMatcher.find()) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("🏨 Hotels in ").append(location).append("\n\n");

        String[] lines = text.split("\\n");
        int hotelCount = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.length() < 5) continue;

            if (pricePattern.matcher(trimmed).find() ||
                    ratingPattern.matcher(trimmed).find() ||
                    trimmed.matches(".*(?:Hotel|Inn|Resort|Hostel|Suite|Lodge|per night|/night).*i?")) {
                sb.append(trimmed).append("\n");
                hotelCount++;
            }
        }

        if (hotelCount < 3) return null;
        return sb.toString().trim();
    }

    // ═══ Helpers ═══

    private String stripHtml(String html) {
        String text = html.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
        text = text.replaceAll("(?is)<style[^>]*>.*?</style>", " ");
        text = text.replaceAll("(?is)<nav[^>]*>.*?</nav>", " ");
        text = text.replaceAll("<[^>]+>", " ");
        text = text.replaceAll("&nbsp;", " ").replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<").replaceAll("&gt;", ">")
                .replaceAll("&quot;", "\"").replaceAll("&#39;", "'");
        return text.replaceAll("\\s{3,}", "\n").trim();
    }

    private String stripTags(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", "")
                .replaceAll("&nbsp;", " ").replaceAll("&amp;", "&")
                .replaceAll("\\s+", " ").trim();
    }

    private String truncate(String s, int max) {
        return (s != null && s.length() > max) ? s.substring(0, max) + "\n... (truncated)" : s;
    }
}
