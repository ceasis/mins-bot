package com.minsbot.skills.backlinkfinder;

import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches competitor pages and extracts outbound link domains. Compares against
 * your own site's outbound domains. Surfaces domains they cite that you don't —
 * candidates for guest posts, partnerships, or directory listings.
 */
@Service
public class BacklinkFinderService {

    private final BacklinkFinderConfig.BacklinkFinderProperties props;
    private final HttpClient http;
    private static final Pattern HREF = Pattern.compile(
            "<a\\s[^>]*href=\"(https?://[^\"]+)\"", Pattern.CASE_INSENSITIVE);

    public BacklinkFinderService(BacklinkFinderConfig.BacklinkFinderProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    public Map<String, Object> find(String yourSite, List<String> competitorSites) throws Exception {
        Set<String> yourDomains = yourSite == null ? Set.of() : extractDomains(yourSite);
        Map<String, Set<String>> domainToCompetitors = new HashMap<>();
        Map<String, Object> perSite = new LinkedHashMap<>();
        int cap = Math.min(competitorSites == null ? 0 : competitorSites.size(), props.getMaxSites());

        for (int i = 0; i < cap; i++) {
            String c = competitorSites.get(i);
            try {
                Set<String> domains = extractDomains(c);
                String competitorHost = URI.create(c).getHost();
                domains.removeIf(d -> d.equals(competitorHost));
                for (String d : domains) {
                    domainToCompetitors.computeIfAbsent(d, k -> new LinkedHashSet<>()).add(c);
                }
                perSite.put(c, Map.of("ok", true, "uniqueDomains", domains.size()));
            } catch (Exception e) {
                perSite.put(c, Map.of("ok", false, "error", e.getMessage()));
            }
        }

        List<Map<String, Object>> opportunities = new ArrayList<>();
        for (var e : domainToCompetitors.entrySet()) {
            if (yourDomains.contains(e.getKey())) continue;
            Map<String, Object> opp = new LinkedHashMap<>();
            opp.put("domain", e.getKey());
            opp.put("citedByCount", e.getValue().size());
            opp.put("citedBy", new ArrayList<>(e.getValue()));
            opportunities.add(opp);
        }
        opportunities.sort((a, b) -> Integer.compare((int) b.get("citedByCount"), (int) a.get("citedByCount")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skill", "backlinkfinder");
        result.put("yourDomainCount", yourDomains.size());
        result.put("competitorsScanned", cap);
        result.put("perSite", perSite);
        result.put("opportunities", opportunities);
        return result;
    }

    private Set<String> extractDomains(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .timeout(Duration.ofMillis(props.getTimeoutMs()))
                .header("User-Agent", props.getUserAgent()).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) throw new RuntimeException("HTTP " + resp.statusCode());
        String body = resp.body();
        if (body.length() > props.getMaxFetchBytes()) body = body.substring(0, props.getMaxFetchBytes());
        Set<String> out = new LinkedHashSet<>();
        Matcher m = HREF.matcher(body);
        while (m.find()) {
            try {
                String host = URI.create(m.group(1)).getHost();
                if (host != null) out.add(host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", ""));
            } catch (Exception ignored) {}
        }
        return out;
    }
}
