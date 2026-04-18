package com.minsbot.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Generic REST caller that uses the IntegrationRegistry metadata + stored credentials
 * to make authenticated API requests.
 *
 * The LLM calls this via IntegrationCallTools to hit any registered integration's REST API.
 */
@Service
public class IntegrationCallService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationCallService.class);

    private final IntegrationRegistry registry;
    private final IntegrationCredentialStore credentials;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public IntegrationCallService(IntegrationRegistry registry, IntegrationCredentialStore credentials) {
        this.registry = registry;
        this.credentials = credentials;
    }

    public Map<String, Object> call(String integrationId, String method, String path, Map<String, String> headers, String body) throws Exception {
        IntegrationRegistry.Integration integration = registry.get(integrationId)
                .orElseThrow(() -> new IllegalArgumentException("unknown integration: " + integrationId));

        if (integration.authType() == IntegrationRegistry.AuthType.BUILTIN) {
            throw new IllegalArgumentException("integration '" + integrationId + "' is built-in — use the native tools/webhooks, not callIntegration");
        }
        if (integration.authType() == IntegrationRegistry.AuthType.SDK) {
            throw new IllegalArgumentException("integration '" + integrationId + "' requires its native SDK (e.g. AWS SDK for Java) — not callable via generic REST");
        }

        if (!credentials.isConfigured(integrationId)) {
            throw new IllegalStateException("integration '" + integrationId + "' not configured. Set credentials via POST /api/integrations/" + integrationId + "/credentials");
        }

        String baseUrl = credentials.getCredential(integrationId, "baseUrl").orElse(integration.baseUrl());
        String fullUrl = normalizeUrl(baseUrl, path);

        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(fullUrl))
                .timeout(Duration.ofSeconds(30));

        // Apply auth header from registry template + stored token
        applyAuth(integration, rb);

        // Custom headers
        if (headers != null) headers.forEach(rb::header);

        // Default content type for body requests
        boolean hasBody = body != null && !body.isEmpty();
        if (hasBody && (headers == null || !headers.containsKey("Content-Type"))) {
            rb.header("Content-Type", "application/json");
        }

        HttpRequest.BodyPublisher publisher = hasBody
                ? HttpRequest.BodyPublishers.ofString(body)
                : HttpRequest.BodyPublishers.noBody();

        String m = method == null ? "GET" : method.toUpperCase();
        switch (m) {
            case "GET" -> rb.GET();
            case "POST" -> rb.POST(publisher);
            case "PUT" -> rb.PUT(publisher);
            case "DELETE" -> rb.DELETE();
            case "PATCH" -> rb.method("PATCH", publisher);
            case "HEAD" -> rb.method("HEAD", HttpRequest.BodyPublishers.noBody());
            default -> throw new IllegalArgumentException("unsupported method: " + method);
        }

        long start = System.currentTimeMillis();
        HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        long elapsed = System.currentTimeMillis() - start;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("integration", integrationId);
        result.put("method", m);
        result.put("url", fullUrl);
        result.put("status", resp.statusCode());
        result.put("elapsedMs", elapsed);
        result.put("headers", resp.headers().map());
        result.put("body", resp.body());
        return result;
    }

    private void applyAuth(IntegrationRegistry.Integration integration, HttpRequest.Builder rb) {
        if (integration.authHeader() == null) return;
        String token = credentials.getCredential(integration.id(), "token").or(() ->
                credentials.getCredential(integration.id(), "apiKey")).or(() ->
                credentials.getCredential(integration.id(), "accessToken")).orElse(null);
        if (token == null || token.isBlank()) {
            log.warn("[Integrations] No token for '{}' — proceeding without auth", integration.id());
            return;
        }
        // Parse "Header-Name: template-with-{token}" spec
        String header = integration.authHeader();
        int colon = header.indexOf(':');
        if (colon < 0) return;
        String headerName = header.substring(0, colon).trim();
        String headerValue = header.substring(colon + 1).trim().replace("{token}", token).replace("{key}", token);
        rb.header(headerName, headerValue);
    }

    private static String normalizeUrl(String baseUrl, String path) {
        if (path == null || path.isBlank()) return baseUrl;
        if (path.startsWith("http://") || path.startsWith("https://")) return path;
        if (baseUrl.endsWith("/") && path.startsWith("/")) return baseUrl + path.substring(1);
        if (!baseUrl.endsWith("/") && !path.startsWith("/")) return baseUrl + "/" + path;
        return baseUrl + path;
    }
}
