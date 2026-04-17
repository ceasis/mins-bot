package com.minsbot.skills.cvelookup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Service
public class CveLookupService {

    private final CveLookupConfig.CveLookupProperties properties;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public CveLookupService(CveLookupConfig.CveLookupProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public Map<String, Object> lookup(String cveId) throws IOException, InterruptedException {
        if (cveId == null || !cveId.matches("(?i)CVE-\\d{4}-\\d{4,}")) {
            throw new IllegalArgumentException("invalid CVE id (expected CVE-YYYY-NNNN+)");
        }
        String url = properties.getApiBase() + "?cveId=" + URLEncoder.encode(cveId.toUpperCase(), StandardCharsets.UTF_8);
        return query(url);
    }

    public Map<String, Object> search(String keyword, int resultsPerPage) throws IOException, InterruptedException {
        if (keyword == null || keyword.isBlank()) throw new IllegalArgumentException("keyword required");
        int limit = Math.max(1, Math.min(100, resultsPerPage));
        String url = properties.getApiBase()
                + "?keywordSearch=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8)
                + "&resultsPerPage=" + limit;
        return query(url);
    }

    private Map<String, Object> query(String url) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                .header("User-Agent", "MinsBot-CveLookup/1.0");
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            b.header("apiKey", properties.getApiKey());
        }
        HttpResponse<String> resp = http.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) throw new IOException("NVD returned HTTP " + resp.statusCode());

        JsonNode root = mapper.readTree(resp.body());
        List<Map<String, Object>> vulns = new ArrayList<>();
        JsonNode array = root.get("vulnerabilities");
        if (array != null && array.isArray()) {
            for (JsonNode v : array) {
                JsonNode cve = v.get("cve");
                if (cve == null) continue;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", text(cve.get("id")));
                entry.put("published", text(cve.get("published")));
                entry.put("lastModified", text(cve.get("lastModified")));
                entry.put("sourceIdentifier", text(cve.get("sourceIdentifier")));
                entry.put("vulnStatus", text(cve.get("vulnStatus")));

                JsonNode descs = cve.get("descriptions");
                if (descs != null && descs.isArray() && descs.size() > 0) {
                    entry.put("description", text(descs.get(0).get("value")));
                }

                JsonNode metrics = cve.get("metrics");
                if (metrics != null) {
                    Map<String, Object> cvss = new LinkedHashMap<>();
                    for (String key : List.of("cvssMetricV31", "cvssMetricV30", "cvssMetricV2")) {
                        JsonNode arr = metrics.get(key);
                        if (arr != null && arr.isArray() && arr.size() > 0) {
                            JsonNode m = arr.get(0);
                            JsonNode data = m.get("cvssData");
                            if (data != null) {
                                cvss.put(key, Map.of(
                                        "baseScore", data.path("baseScore").asDouble(0),
                                        "severity", text(data.get("baseSeverity")),
                                        "vector", text(data.get("vectorString"))
                                ));
                            }
                        }
                    }
                    if (!cvss.isEmpty()) entry.put("cvss", cvss);
                }

                JsonNode refs = cve.get("references");
                List<String> refUrls = new ArrayList<>();
                if (refs != null && refs.isArray()) {
                    for (JsonNode r : refs) refUrls.add(text(r.get("url")));
                }
                entry.put("references", refUrls);
                vulns.add(entry);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalResults", root.path("totalResults").asInt(0));
        result.put("resultsReturned", vulns.size());
        result.put("vulnerabilities", vulns);
        return result;
    }

    private static String text(JsonNode n) {
        return (n == null || n.isNull()) ? null : n.asText();
    }
}
