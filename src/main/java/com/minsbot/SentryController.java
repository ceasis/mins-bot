package com.minsbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Backend proxies for Sentry Mode overlays:
 *   GET /api/sentry/flights?lamin=&lomin=&lamax=&lomax=  → OpenSky Network state vectors
 *   GET /api/sentry/geocode?q=...                       → Nominatim (OSM) place lookup
 *
 * <p>Both targets are free, no key, but rate-limited. The proxy exists to:
 * (1) avoid CORS in the WebView, (2) shape responses into something compact
 * the frontend can render, (3) provide a single chokepoint for caching/throttling
 * if/when poll volume becomes a problem.
 */
@RestController
@RequestMapping("/api/sentry")
public class SentryController {

    private static final Logger log = LoggerFactory.getLogger(SentryController.class);

    private static final String OPENSKY_BASE = "https://opensky-network.org/api/states/all";
    private static final String NOMINATIM_BASE = "https://nominatim.openstreetmap.org/search";
    private static final String UA = "MinsBot/1.0 (https://mins.io; sentry-mode)";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Returns aircraft state vectors inside a bbox. Each entry:
     *   {icao, callsign, country, lon, lat, alt, vel, hdg, onGround}
     * On error returns {flights: [], error: "..."}.
     */
    @GetMapping(value = "/flights", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> flights(
            @RequestParam("lamin") double lamin,
            @RequestParam("lomin") double lomin,
            @RequestParam("lamax") double lamax,
            @RequestParam("lomax") double lomax) {

        // OpenSky requires lamin < lamax, lomin < lomax
        if (lamin >= lamax || lomin >= lomax) {
            return Map.of("flights", List.of(), "error", "invalid bbox");
        }
        String url = OPENSKY_BASE
                + "?lamin=" + lamin + "&lomin=" + lomin
                + "&lamax=" + lamax + "&lomax=" + lomax;

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", UA)
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return Map.of("flights", List.of(), "error", "opensky http " + resp.statusCode());
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode arr = root.get("states");
            List<Map<String, Object>> out = new ArrayList<>();
            if (arr != null && arr.isArray()) {
                for (JsonNode s : arr) {
                    if (!s.isArray() || s.size() < 11) continue;
                    JsonNode lonNode = s.get(5);
                    JsonNode latNode = s.get(6);
                    if (lonNode == null || lonNode.isNull() || latNode == null || latNode.isNull()) continue;
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("icao",     textOrNull(s.get(0)));
                    f.put("callsign", textOrNull(s.get(1)));
                    f.put("country",  textOrNull(s.get(2)));
                    f.put("lon",      lonNode.asDouble());
                    f.put("lat",      latNode.asDouble());
                    f.put("alt",      doubleOrNull(s.get(7)));
                    f.put("onGround", s.get(8) != null && s.get(8).asBoolean(false));
                    f.put("vel",      doubleOrNull(s.get(9)));
                    f.put("hdg",      doubleOrNull(s.get(10)));
                    out.add(f);
                }
            }
            return Map.of("flights", out, "time", root.path("time").asLong(0));
        } catch (Exception e) {
            log.warn("[Sentry/flights] {}: {}", url, e.getMessage());
            return Map.of("flights", List.of(), "error", e.getMessage());
        }
    }

    /**
     * Free-form place lookup via OSM Nominatim. Returns the first hit:
     *   {lat, lon, displayName, bbox: [south, north, west, east]}
     */
    @GetMapping(value = "/geocode", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> geocode(@RequestParam("q") String q) {
        if (q == null || q.isBlank()) return Map.of("error", "missing q");
        String url = NOMINATIM_BASE
                + "?format=json&limit=1&q="
                + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8);
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(6))
                    .header("User-Agent", UA)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return Map.of("error", "nominatim http " + resp.statusCode());
            }
            JsonNode arr = mapper.readTree(resp.body());
            if (!arr.isArray() || arr.isEmpty()) {
                return Map.of("error", "no match");
            }
            JsonNode top = arr.get(0);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("lat",         Double.parseDouble(top.path("lat").asText("0")));
            out.put("lon",         Double.parseDouble(top.path("lon").asText("0")));
            out.put("displayName", top.path("display_name").asText(""));

            JsonNode bb = top.path("boundingbox");
            if (bb.isArray() && bb.size() == 4) {
                // Nominatim returns ["south", "north", "west", "east"] as strings
                List<Double> bbox = new ArrayList<>(4);
                for (JsonNode n : bb) bbox.add(Double.parseDouble(n.asText("0")));
                out.put("bbox", bbox);
            }
            return out;
        } catch (Exception e) {
            log.warn("[Sentry/geocode] {}: {}", q, e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }

    /**
     * CCTV / public-camera lookup — Phase 1 stub. The frontend currently routes
     * any "cctv for X" / "cameras in X" command to YouTube live search via the
     * video panel. This endpoint exists as the forward-compat hook for plugging
     * in regional traffic-camera catalogs (NYC DOT, Caltrans, TfL Jam Cams,
     * 511NY, etc.) without changing the frontend contract.
     *
     * <p>Returns {@code {fallback: "youtube", q: <place>}} for now — frontend
     * already implements the YouTube embed path. Add catalog logic here when a
     * region's JSON feed is wired up; switch the response to {@code {cameras:
     * [{id, name, lat, lon, snapshotUrl}, ...]}}.
     */
    @GetMapping(value = "/cctv", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> cctv(@RequestParam(value = "q", required = false) String q) {
        // TODO: layer NYC DOT / 511NY / Caltrans / TfL catalogs here when wired.
        return Map.of("fallback", "youtube", "q", q == null ? "" : q);
    }

    /**
     * Snapshot proxy — fetches a JPEG from a whitelisted host and streams it
     * back so the WebView can render it without CORS/referrer hassle. Empty
     * whitelist for now; populate when a real catalog is added in {@link #cctv}.
     */
    @GetMapping(value = "/cctv/snapshot")
    public org.springframework.http.ResponseEntity<byte[]> cctvSnapshot(@RequestParam("url") String url) {
        if (!isWhitelistedSnapshotHost(url)) {
            return org.springframework.http.ResponseEntity.badRequest().build();
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(6))
                    .header("User-Agent", UA)
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                return org.springframework.http.ResponseEntity.status(resp.statusCode()).build();
            }
            return org.springframework.http.ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .cacheControl(org.springframework.http.CacheControl.noCache())
                    .body(resp.body());
        } catch (Exception e) {
            log.warn("[Sentry/cctv-snapshot] {}: {}", url, e.getMessage());
            return org.springframework.http.ResponseEntity.status(502).build();
        }
    }

    /** Allow only known traffic-cam hosts. Empty for Phase 1; expand alongside the catalog. */
    private static boolean isWhitelistedSnapshotHost(String url) {
        if (url == null) return false;
        try {
            String host = URI.create(url).getHost();
            if (host == null) return false;
            host = host.toLowerCase();
            return host.endsWith(".nyctmc.org")
                || host.endsWith(".dot.ny.gov")
                || host.endsWith(".511ny.org")
                || host.endsWith(".dot.ca.gov")
                || host.endsWith(".wsdot.com")
                || host.endsWith(".tfl.gov.uk");
        } catch (Exception e) {
            return false;
        }
    }

    private static String textOrNull(JsonNode n) {
        if (n == null || n.isNull()) return null;
        String s = n.asText("").trim();
        return s.isEmpty() ? null : s;
    }
    private static Double doubleOrNull(JsonNode n) {
        if (n == null || n.isNull()) return null;
        try { return n.asDouble(); } catch (Exception e) { return null; }
    }
}
