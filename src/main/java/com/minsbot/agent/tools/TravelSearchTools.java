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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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
 *
 * <p>Results always include:
 * <ul>
 *   <li>Direct booking link (Google Flights / Google Hotels URL)</li>
 *   <li>Per-person / per-night prices extracted from results</li>
 *   <li>Cost breakdown: per-person, total for group, per-night, total nights</li>
 * </ul>
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

    /** Matches prices like ₱12,345 or $1,234.56 or €500 or PHP 12,345 */
    private static final Pattern PRICE_PATTERN = Pattern.compile(
            "(?:₱|\\$|€|£|PHP|USD|EUR|GBP)\\s?([\\d,]+(?:\\.\\d{2})?)");

    public TravelSearchTools(ToolExecutionNotifier notifier, PlaywrightService pw) {
        this.notifier = notifier;
        this.pw = pw;
    }

    // ═══ Flight Search ═══

    @Tool(description = "Search for flights between two locations. Searches Google Flights, Kayak, and other sites. " +
            "Returns flight options with prices, airlines, durations, booking links for multiple sites " +
            "(Google, Kayak, Skyscanner, Trip.com), and a cost breakdown (per person and total for group). " +
            "Use this when the user asks to find or research flights.")
    public String searchFlights(
            @ToolParam(description = "Origin city or airport code, e.g. 'Manila' or 'MNL'") String from,
            @ToolParam(description = "Destination city or airport code, e.g. 'Taipei' or 'TPE'") String to,
            @ToolParam(description = "Departure date in YYYY-MM-DD format, e.g. '2026-04-15'") String departDate,
            @ToolParam(description = "Return date in YYYY-MM-DD format, or 'one-way' for one-way trip") String returnDate,
            @ToolParam(description = "Number of passengers (e.g. 4 for a family of 4)") double passengersRaw) {

        int passengers = Math.max(1, (int) Math.round(passengersRaw));
        notifier.notify("Searching flights: " + from + " → " + to + " (" + passengers + " pax)");
        log.info("[TravelSearch] Flights: {} → {} on {}, return {}, {} pax", from, to, departDate, returnDate, passengers);

        boolean isRoundTrip = returnDate != null && !returnDate.equalsIgnoreCase("one-way") && !returnDate.isBlank();
        String googleUrl = buildGoogleFlightsUrl(from, to, departDate, returnDate);
        String kayakUrl = buildKayakFlightsUrl(from, to, departDate, returnDate);
        String skyscannerUrl = buildSkyscannerFlightsUrl(from, to, departDate, returnDate);
        String tripUrl = buildTripComFlightsUrl(from, to, departDate, returnDate);

        // Collect scraped content from multiple sources
        StringBuilder scrapedContent = new StringBuilder();
        List<PriceEntry> allPrices = new ArrayList<>();
        String bestSource = null;

        // Strategy 1: Google Flights via Playwright
        notifier.notify("Checking Google Flights...");
        String text = scrapePageText(googleUrl);
        if (text != null && text.length() >= 100) {
            bestSource = "Google Flights";
            allPrices.addAll(extractPrices(text));
            scrapedContent.append("--- Google Flights ---\n").append(truncate(filterFlightLines(text), 2000)).append("\n\n");
        }

        // Strategy 2: Kayak via Playwright
        notifier.notify("Checking Kayak...");
        text = scrapePageText(kayakUrl);
        if (text != null && text.length() >= 100) {
            if (bestSource == null) bestSource = "Kayak";
            allPrices.addAll(extractPrices(text));
            scrapedContent.append("--- Kayak ---\n").append(truncate(filterFlightLines(text), 2000)).append("\n\n");
        }

        // Strategy 3: DuckDuckGo multi-site search fallback
        if (scrapedContent.isEmpty()) {
            notifier.notify("Searching travel sites...");
            String ddgQuery = "flights from " + from + " to " + to + " " + departDate
                    + " price kayak OR skyscanner OR trip.com";
            String ddg = tryDdgSearch(ddgQuery);
            if (ddg != null && !ddg.isBlank()) {
                scrapedContent.append("--- Web Search Results ---\n").append(ddg).append("\n\n");
            }
        }

        // Build result
        StringBuilder sb = new StringBuilder();
        sb.append("✈ FLIGHT SEARCH: ").append(from).append(" → ").append(to).append("\n");
        sb.append("Date: ").append(departDate);
        if (isRoundTrip) sb.append(" — Return: ").append(returnDate);
        sb.append("\nPassengers: ").append(passengers).append("\n\n");

        // Booking links for all sites
        sb.append("🔗 BOOKING LINKS:\n");
        sb.append("  Google Flights: ").append(googleUrl).append("\n");
        sb.append("  Kayak: ").append(kayakUrl).append("\n");
        sb.append("  Skyscanner: ").append(skyscannerUrl).append("\n");
        sb.append("  Trip.com: ").append(tripUrl).append("\n\n");

        // Scraped content
        if (scrapedContent.length() > 0) {
            sb.append(scrapedContent);
        } else {
            sb.append("⚠ Could not scrape flight results directly. Use the booking links above.\n\n");
        }

        // Price breakdown
        appendFlightPriceBreakdown(sb, allPrices, passengers, bestSource);

        return sb.toString();
    }

    @Tool(description = "Search for hotels in a location. Searches Google Hotels, Booking.com, Agoda, Kayak and other sites. " +
            "Returns hotel options with per-night prices, ratings, booking links for multiple sites " +
            "(Google, Booking.com, Agoda, Kayak, Trip.com), and a cost breakdown (per night, total for stay, total for all rooms). " +
            "Use this when the user asks to find or research hotels.")
    public String searchHotels(
            @ToolParam(description = "Location to search hotels in, e.g. 'Taipei Taiwan'") String location,
            @ToolParam(description = "Check-in date in YYYY-MM-DD format") String checkinDate,
            @ToolParam(description = "Check-out date in YYYY-MM-DD format") String checkoutDate,
            @ToolParam(description = "Number of rooms needed (e.g. 2 for a family needing 2 rooms)") double roomsRaw) {

        int rooms = Math.max(1, (int) Math.round(roomsRaw));
        long nights = calculateNights(checkinDate, checkoutDate);
        notifier.notify("Searching hotels in: " + location + " (" + rooms + " rooms, " + nights + " nights)");
        log.info("[TravelSearch] Hotels: {} from {} to {}, {} rooms, {} nights", location, checkinDate, checkoutDate, rooms, nights);

        String googleUrl = buildGoogleHotelsUrl(location, checkinDate, checkoutDate);
        String bookingUrl = buildBookingComUrl(location, checkinDate, checkoutDate, rooms);
        String agodaUrl = buildAgodaUrl(location, checkinDate, checkoutDate, rooms);
        String kayakUrl = buildKayakHotelsUrl(location, checkinDate, checkoutDate);
        String tripUrl = buildTripComHotelsUrl(location, checkinDate, checkoutDate);

        // Collect scraped content from multiple sources
        StringBuilder scrapedContent = new StringBuilder();
        List<PriceEntry> allPrices = new ArrayList<>();
        String bestSource = null;

        // Strategy 1: Google Hotels via Playwright
        notifier.notify("Checking Google Hotels...");
        String text = scrapePageText(googleUrl);
        if (text != null && text.length() >= 100) {
            bestSource = "Google Hotels";
            allPrices.addAll(extractPrices(text));
            scrapedContent.append("--- Google Hotels ---\n").append(truncate(filterHotelLines(text), 2000)).append("\n\n");
        }

        // Strategy 2: Booking.com via Playwright
        notifier.notify("Checking Booking.com...");
        text = scrapePageText(bookingUrl);
        if (text != null && text.length() >= 100) {
            if (bestSource == null) bestSource = "Booking.com";
            allPrices.addAll(extractPrices(text));
            scrapedContent.append("--- Booking.com ---\n").append(truncate(filterHotelLines(text), 2000)).append("\n\n");
        }

        // Strategy 3: Agoda via Playwright
        notifier.notify("Checking Agoda...");
        text = scrapePageText(agodaUrl);
        if (text != null && text.length() >= 100) {
            if (bestSource == null) bestSource = "Agoda";
            allPrices.addAll(extractPrices(text));
            scrapedContent.append("--- Agoda ---\n").append(truncate(filterHotelLines(text), 2000)).append("\n\n");
        }

        // Strategy 4: DuckDuckGo multi-site search fallback
        if (scrapedContent.isEmpty()) {
            notifier.notify("Searching travel sites...");
            String ddgQuery = "hotels in " + location + " " + checkinDate + " to " + checkoutDate
                    + " price per night booking.com OR agoda OR kayak";
            String ddg = tryDdgSearch(ddgQuery);
            if (ddg != null && !ddg.isBlank()) {
                scrapedContent.append("--- Web Search Results ---\n").append(ddg).append("\n\n");
            }
        }

        // Build result
        StringBuilder sb = new StringBuilder();
        sb.append("🏨 HOTEL SEARCH: ").append(location).append("\n");
        sb.append("Dates: ").append(checkinDate).append(" to ").append(checkoutDate);
        sb.append(" (").append(nights).append(" nights)\n");
        sb.append("Rooms: ").append(rooms).append("\n\n");

        // Booking links for all sites
        sb.append("🔗 BOOKING LINKS:\n");
        sb.append("  Google Hotels: ").append(googleUrl).append("\n");
        sb.append("  Booking.com: ").append(bookingUrl).append("\n");
        sb.append("  Agoda: ").append(agodaUrl).append("\n");
        sb.append("  Kayak: ").append(kayakUrl).append("\n");
        sb.append("  Trip.com: ").append(tripUrl).append("\n\n");

        // Scraped content
        if (scrapedContent.length() > 0) {
            sb.append(scrapedContent);
        } else {
            sb.append("⚠ Could not scrape hotel results directly. Use the booking links above.\n\n");
        }

        // Price breakdown
        appendHotelPriceBreakdown(sb, allPrices, nights, rooms, bestSource);

        return sb.toString();
    }

    // ═══ Page scraper (shared by all sources) ═══

    /** Try to get page text via Playwright. Returns null on failure. */
    private String scrapePageText(String url) {
        try {
            log.info("[TravelSearch] Scraping: {}", url);
            String text = pw.getPageText(url);
            if (text == null || text.length() < 100) {
                String html = pw.getPageHtml(url);
                if (html != null && html.length() > 200) {
                    text = stripHtml(html);
                }
            }
            return (text != null && text.length() >= 100) ? text : null;
        } catch (Exception e) {
            log.warn("[TravelSearch] Scrape failed for {}: {}", url, e.getMessage());
            return null;
        }
    }

    // ═══ Price breakdown formatters ═══

    private void appendFlightPriceBreakdown(StringBuilder sb, List<PriceEntry> allPrices,
                                             int passengers, String bestSource) {
        // Deduplicate and sort
        List<PriceEntry> prices = deduplicatePrices(allPrices);

        sb.append("--- COST BREAKDOWN ---\n");
        if (bestSource != null) sb.append("(prices from ").append(bestSource).append(")\n");

        if (!prices.isEmpty()) {
            PriceEntry cheapest = prices.get(0);
            PriceEntry mid = prices.size() > 2 ? prices.get(prices.size() / 2) : cheapest;
            PriceEntry expensive = prices.get(prices.size() - 1);

            sb.append("Cheapest flight: ").append(cheapest.display).append(" per person\n");
            if (prices.size() > 1) {
                sb.append("Mid-range: ").append(mid.display).append(" per person\n");
                sb.append("Premium: ").append(expensive.display).append(" per person\n");
            }
            sb.append("\n");
            sb.append(String.format("CHEAPEST TOTAL (%d passengers): %s%n",
                    passengers, formatPrice(cheapest.amount * passengers)));
            if (prices.size() > 1) {
                sb.append(String.format("MID-RANGE TOTAL (%d passengers): %s%n",
                        passengers, formatPrice(mid.amount * passengers)));
            }
            sb.append(String.format("Prices found: %d options (lowest %s — highest %s)%n",
                    prices.size(), cheapest.display, expensive.display));
        } else {
            sb.append("Could not extract exact prices. Check the booking links above for live pricing.\n");
            sb.append("Multiply the per-person price × ").append(passengers).append(" passengers for total.\n");
        }
    }

    private void appendHotelPriceBreakdown(StringBuilder sb, List<PriceEntry> allPrices,
                                            long nights, int rooms, String bestSource) {
        List<PriceEntry> prices = deduplicatePrices(allPrices);

        sb.append("--- COST BREAKDOWN ---\n");
        if (bestSource != null) sb.append("(prices from ").append(bestSource).append(")\n");

        if (!prices.isEmpty()) {
            PriceEntry cheapest = prices.get(0);
            PriceEntry mid = prices.size() > 2 ? prices.get(prices.size() / 2) : cheapest;
            PriceEntry expensive = prices.get(prices.size() - 1);

            sb.append("Cheapest: ").append(cheapest.display).append(" per night\n");
            if (prices.size() > 1) {
                sb.append("Mid-range: ").append(mid.display).append(" per night\n");
                sb.append("Premium: ").append(expensive.display).append(" per night\n");
            }
            sb.append("\n");
            sb.append(String.format("CHEAPEST TOTAL (%d nights × %d rooms): %s%n",
                    nights, rooms, formatPrice(cheapest.amount * nights * rooms)));
            if (prices.size() > 1) {
                sb.append(String.format("MID-RANGE TOTAL (%d nights × %d rooms): %s%n",
                        nights, rooms, formatPrice(mid.amount * nights * rooms)));
            }
            sb.append(String.format("Prices found: %d options (lowest %s — highest %s per night)%n",
                    prices.size(), cheapest.display, expensive.display));
        } else {
            sb.append("Could not extract exact prices. Check the booking links above for live pricing.\n");
            sb.append("Multiply the per-night price × ").append(nights).append(" nights");
            sb.append(" × ").append(rooms).append(" rooms for total.\n");
        }
    }

    // ═══ URL builders — Google ═══

    private String buildGoogleFlightsUrl(String from, String to, String departDate, String returnDate) {
        String query = "Flights from " + from + " to " + to + " on " + departDate;
        if (returnDate != null && !returnDate.equalsIgnoreCase("one-way") && !returnDate.isBlank()) {
            query += " return " + returnDate;
        }
        return "https://www.google.com/travel/flights?q=" +
                URLEncoder.encode(query, StandardCharsets.UTF_8);
    }

    private String buildGoogleHotelsUrl(String location, String checkin, String checkout) {
        String query = "Hotels in " + location + " " + checkin + " to " + checkout;
        return "https://www.google.com/travel/search?q=" +
                URLEncoder.encode(query, StandardCharsets.UTF_8);
    }

    // ═══ URL builders — Kayak ═══

    private String buildKayakFlightsUrl(String from, String to, String departDate, String returnDate) {
        // Kayak: /flights/FROM-TO/departDate/returnDate
        String enc = enc(from) + "-" + enc(to) + "/" + departDate;
        if (returnDate != null && !returnDate.equalsIgnoreCase("one-way") && !returnDate.isBlank()) {
            enc += "/" + returnDate;
        }
        return "https://www.kayak.com/flights/" + enc + "?sort=bestflight_a";
    }

    private String buildKayakHotelsUrl(String location, String checkin, String checkout) {
        return "https://www.kayak.com/hotels/" +
                URLEncoder.encode(location, StandardCharsets.UTF_8).replace("+", "-") +
                "/" + checkin + "/" + checkout + "/2guests";
    }

    // ═══ URL builders — Booking.com ═══

    private String buildBookingComUrl(String location, String checkin, String checkout, int rooms) {
        return "https://www.booking.com/searchresults.html?ss=" +
                URLEncoder.encode(location, StandardCharsets.UTF_8) +
                "&checkin=" + checkin +
                "&checkout=" + checkout +
                "&group_adults=2&no_rooms=" + rooms +
                "&selected_currency=PHP";
    }

    // ═══ URL builders — Agoda ═══

    private String buildAgodaUrl(String location, String checkin, String checkout, int rooms) {
        long nights = calculateNights(checkin, checkout);
        return "https://www.agoda.com/search?q=" +
                URLEncoder.encode(location, StandardCharsets.UTF_8) +
                "&checkIn=" + checkin +
                "&los=" + nights +
                "&rooms=" + rooms +
                "&adults=2&currency=PHP";
    }

    // ═══ URL builders — Skyscanner ═══

    private String buildSkyscannerFlightsUrl(String from, String to, String departDate, String returnDate) {
        // Skyscanner uses city/airport names in the URL path
        String path = enc(from) + "/" + enc(to) + "/" + departDate.replace("-", "");
        if (returnDate != null && !returnDate.equalsIgnoreCase("one-way") && !returnDate.isBlank()) {
            path += "/" + returnDate.replace("-", "");
        }
        return "https://www.skyscanner.com/transport/flights/" + path + "/";
    }

    // ═══ URL builders — Trip.com ═══

    private String buildTripComFlightsUrl(String from, String to, String departDate, String returnDate) {
        return "https://www.trip.com/flights/" +
                URLEncoder.encode(from + " to " + to, StandardCharsets.UTF_8) +
                "?dcity=" + enc(from) + "&acity=" + enc(to) +
                "&ddate=" + departDate +
                (returnDate != null && !returnDate.equalsIgnoreCase("one-way") && !returnDate.isBlank()
                        ? "&rdate=" + returnDate : "");
    }

    private String buildTripComHotelsUrl(String location, String checkin, String checkout) {
        return "https://www.trip.com/hotels/list?city=" +
                URLEncoder.encode(location, StandardCharsets.UTF_8) +
                "&checkin=" + checkin + "&checkout=" + checkout;
    }

    /** URL-safe encode for path segments (spaces → dashes, lowercase). */
    private static String enc(String s) {
        return s.trim().toLowerCase().replaceAll("\\s+", "-");
    }

    // ═══ Price extraction ═══

    /** Extract all prices from text, sorted ascending. */
    private List<PriceEntry> extractPrices(String text) {
        List<PriceEntry> prices = new ArrayList<>();
        Matcher m = PRICE_PATTERN.matcher(text);
        while (m.find()) {
            try {
                String numStr = m.group(1).replace(",", "");
                double amount = Double.parseDouble(numStr);
                // Filter out unreasonable prices (too small = not a flight/hotel price)
                if (amount >= 10 && amount < 1_000_000) {
                    String display = m.group(0).trim();
                    prices.add(new PriceEntry(amount, display));
                }
            } catch (NumberFormatException ignored) {}
        }
        // Sort ascending by amount
        prices.sort((a, b) -> Double.compare(a.amount, b.amount));
        // Deduplicate consecutive same amounts
        List<PriceEntry> deduped = new ArrayList<>();
        double lastAmount = -1;
        for (PriceEntry p : prices) {
            if (p.amount != lastAmount) {
                deduped.add(p);
                lastAmount = p.amount;
            }
        }
        return deduped;
    }

    /** Deduplicate and sort a combined list of prices from multiple sources. */
    private List<PriceEntry> deduplicatePrices(List<PriceEntry> prices) {
        prices.sort((a, b) -> Double.compare(a.amount, b.amount));
        List<PriceEntry> deduped = new ArrayList<>();
        double lastAmount = -1;
        for (PriceEntry p : prices) {
            if (p.amount != lastAmount) {
                deduped.add(p);
                lastAmount = p.amount;
            }
        }
        return deduped;
    }

    private String formatPrice(double amount) {
        if (amount == (long) amount) {
            return String.format("₱%,.0f", amount);
        }
        return String.format("₱%,.2f", amount);
    }

    private record PriceEntry(double amount, String display) {}

    // ═══ Line filtering ═══

    private static final Pattern TIME_PATTERN = Pattern.compile(
            "\\d{1,2}:\\d{2}\\s*(?:AM|PM|am|pm)?\\s*[–\\-]\\s*\\d{1,2}:\\d{2}\\s*(?:AM|PM|am|pm)?");
    private static final Pattern DURATION_PATTERN = Pattern.compile(
            "\\d+\\s*(?:hr?|hour)\\s*\\d*\\s*(?:min|m)?");

    private String filterFlightLines(String text) {
        StringBuilder sb = new StringBuilder();
        String[] lines = text.split("\\n");
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.length() < 5) continue;
            if (PRICE_PATTERN.matcher(t).find() ||
                    TIME_PATTERN.matcher(t).find() ||
                    DURATION_PATTERN.matcher(t).find() ||
                    t.matches("(?i).*(?:nonstop|1 stop|2 stops).*") ||
                    t.matches("(?i).*(?:Airlines?|Air |Philippine|Cebu|PAL|EVA|Cathay|ANA|Japan|Korean|Delta|United|Spirit|JetStar|AirAsia|Scoot).*")) {
                sb.append(t).append("\n");
            }
        }
        return sb.length() > 0 ? sb.toString() : text;
    }

    private String filterHotelLines(String text) {
        StringBuilder sb = new StringBuilder();
        String[] lines = text.split("\\n");
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.length() < 5) continue;
            if (PRICE_PATTERN.matcher(t).find() ||
                    t.matches("(?i).*\\d\\.\\d\\s*(?:star|★|\\(\\d+\\)|out of).*") ||
                    t.matches("(?i).*(?:Hotel|Inn|Resort|Hostel|Suite|Lodge|Villa|Apartelle|Pension|B&B|Airbnb|per night|/night).*")) {
                sb.append(t).append("\n");
            }
        }
        return sb.length() > 0 ? sb.toString() : text;
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
        return String.join("\n\n", results);
    }

    // ═══ Helpers ═══

    private long calculateNights(String checkin, String checkout) {
        try {
            LocalDate in = LocalDate.parse(checkin, DateTimeFormatter.ISO_LOCAL_DATE);
            LocalDate out = LocalDate.parse(checkout, DateTimeFormatter.ISO_LOCAL_DATE);
            long nights = ChronoUnit.DAYS.between(in, out);
            return nights > 0 ? nights : 1;
        } catch (Exception e) {
            return 1;
        }
    }

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
