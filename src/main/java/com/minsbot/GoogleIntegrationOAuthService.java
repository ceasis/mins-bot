package com.minsbot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-integration Google OAuth (users opt in to Analytics, Gmail, Calendar, etc. separately).
 * Tokens are stored under {@code ~/mins_bot_data/google_integrations/tokens.json}.
 */
@Service
public class GoogleIntegrationOAuthService {

    private static final Logger log = LoggerFactory.getLogger(GoogleIntegrationOAuthService.class);

    public static final List<String> INTEGRATION_IDS = List.of(
            "analytics", "gmail", "calendar", "drive", "maps", "workspace");

    private static final String AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String REVOKE_URL = "https://oauth2.googleapis.com/revoke";

    private static final Map<String, List<String>> SCOPES_BY_INTEGRATION = Map.of(
            "analytics", List.of("https://www.googleapis.com/auth/analytics.readonly"),
            "gmail", List.of(
                    "https://www.googleapis.com/auth/gmail.readonly",
                    "https://www.googleapis.com/auth/gmail.send"),
            "calendar", List.of(
                    "https://www.googleapis.com/auth/calendar.events",
                    "https://www.googleapis.com/auth/calendar.readonly"),
            "drive", List.of("https://www.googleapis.com/auth/drive.readonly"),
            "maps", List.of(
                    "openid",
                    "https://www.googleapis.com/auth/userinfo.email",
                    "https://www.googleapis.com/auth/userinfo.profile"),
            "workspace", List.of(
                    "openid",
                    "https://www.googleapis.com/auth/userinfo.email",
                    "https://www.googleapis.com/auth/userinfo.profile",
                    "https://www.googleapis.com/auth/contacts.readonly")
    );

    private final GoogleIntegrationConfig.GoogleIntegrationProperties properties;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final String effectiveRedirectUri;
    /** Same property names as TelliChat ({@code spring.security.oauth2.client.registration.google.*}). */
    private final String springGoogleClientId;
    private final String springGoogleClientSecret;

    private final ConcurrentHashMap<String, PendingState> pendingByState = new ConcurrentHashMap<>();

    public GoogleIntegrationOAuthService(
            GoogleIntegrationConfig.GoogleIntegrationProperties properties,
            ObjectMapper objectMapper,
            RestTemplate restTemplate,
            @Value("${server.port:8765}") int serverPort,
            @Value("${spring.security.oauth2.client.registration.google.client-id:}") String springGoogleClientId,
            @Value("${spring.security.oauth2.client.registration.google.client-secret:}") String springGoogleClientSecret) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
        this.springGoogleClientId = springGoogleClientId != null ? springGoogleClientId.strip() : "";
        this.springGoogleClientSecret = springGoogleClientSecret != null ? springGoogleClientSecret.strip() : "";
        String configured = properties.getRedirectUri() != null ? properties.getRedirectUri().strip() : "";
        this.effectiveRedirectUri = !configured.isEmpty()
                ? configured
                : "http://127.0.0.1:" + serverPort + "/api/integrations/google/oauth2/callback";
    }

    private String effectiveClientId() {
        if (!springGoogleClientId.isBlank()) {
            return springGoogleClientId;
        }
        return properties.getClientId() != null ? properties.getClientId().strip() : "";
    }

    private String effectiveClientSecret() {
        if (!springGoogleClientSecret.isBlank()) {
            return springGoogleClientSecret;
        }
        return properties.getClientSecret() != null ? properties.getClientSecret().strip() : "";
    }

    public String getEffectiveRedirectUri() {
        return effectiveRedirectUri;
    }

    public boolean isOAuthConfigured() {
        return !effectiveClientId().isBlank() && !effectiveClientSecret().isBlank();
    }

    public URI buildAuthorizationUri(String integrationId) {
        if (!isOAuthConfigured()) {
            throw new IllegalStateException("Google OAuth client ID and secret are not set (Setup tab).");
        }
        if (!SCOPES_BY_INTEGRATION.containsKey(integrationId)) {
            throw new IllegalArgumentException("Unknown integration: " + integrationId);
        }
        StoredGoogleTokens existing = loadTokens().orElse(null);
        if (existing != null && existing.enabledIntegrations.contains(integrationId)) {
            throw new IllegalStateException("Already connected: " + integrationId);
        }
        LinkedHashSet<String> scopeUnion = new LinkedHashSet<>();
        if (existing != null) {
            for (String enabled : existing.enabledIntegrations) {
                scopeUnion.addAll(SCOPES_BY_INTEGRATION.getOrDefault(enabled, List.of()));
            }
        }
        scopeUnion.addAll(SCOPES_BY_INTEGRATION.getOrDefault(integrationId, List.of()));

        String state = UUID.randomUUID().toString().replace("-", "");
        pendingByState.put(state, new PendingState(integrationId, Instant.now().plusSeconds(600)));

        String scopeStr = String.join(" ", scopeUnion);
        return UriComponentsBuilder.fromUriString(AUTH_URL)
                .queryParam("client_id", effectiveClientId())
                .queryParam("redirect_uri", effectiveRedirectUri)
                .queryParam("response_type", "code")
                .queryParam("scope", scopeStr)
                .queryParam("access_type", "offline")
                .queryParam("prompt", "consent")
                .queryParam("include_granted_scopes", "true")
                .queryParam("state", state)
                .encode(StandardCharsets.UTF_8)
                .build()
                .toUri();
    }

    public String consumeAndValidateState(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        PendingState p = pendingByState.remove(state);
        if (p == null || Instant.now().isAfter(p.expires())) {
            return null;
        }
        return p.integrationId();
    }

    public void exchangeCodeAndStore(String integrationId, String code) throws IOException {
        if (!isOAuthConfigured()) {
            throw new IllegalStateException("OAuth not configured");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", effectiveClientId());
        form.add("client_secret", effectiveClientSecret());
        form.add("redirect_uri", effectiveRedirectUri);
        form.add("grant_type", "authorization_code");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(form, headers);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = restTemplate.postForObject(TOKEN_URL, entity, Map.class);
        if (body == null || body.get("access_token") == null) {
            throw new IOException("Token response missing access_token");
        }

        StoredGoogleTokens tokens = loadTokens().orElse(new StoredGoogleTokens());
        tokens.accessToken = String.valueOf(body.get("access_token"));
        Number exp = (Number) body.get("expires_in");
        long sec = exp != null ? exp.longValue() : 3600L;
        tokens.accessTokenExpiryEpochMs = System.currentTimeMillis() + sec * 1000L;
        Object rt = body.get("refresh_token");
        if (rt != null && !String.valueOf(rt).isBlank()) {
            tokens.refreshToken = String.valueOf(rt);
        }
        tokens.enabledIntegrations.add(integrationId);
        saveTokens(tokens);
        log.info("[GoogleOAuth] Stored tokens; enabled integration: {}", integrationId);
    }

    /**
     * Opt out of one integration. If none remain, revokes the refresh token and deletes the file.
     */
    public void disconnect(String integrationId) throws IOException {
        if (!SCOPES_BY_INTEGRATION.containsKey(integrationId)) {
            throw new IllegalArgumentException("Unknown integration: " + integrationId);
        }
        StoredGoogleTokens tokens = loadTokens().orElse(null);
        if (tokens == null) {
            return;
        }
        if (!tokens.enabledIntegrations.remove(integrationId)) {
            return;
        }
        if (tokens.enabledIntegrations.isEmpty()) {
            revokeSilently(tokens.refreshToken);
            Path p = tokenFilePath();
            Files.deleteIfExists(p);
            log.info("[GoogleOAuth] Revoked and removed token file (no integrations left)");
            return;
        }
        saveTokens(tokens);
        log.info("[GoogleOAuth] Disconnected integration: {} (others still enabled)", integrationId);
    }

    private void revokeSilently(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("token", refreshToken);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            restTemplate.postForEntity(REVOKE_URL, new HttpEntity<>(form, headers), String.class);
        } catch (Exception e) {
            log.debug("[GoogleOAuth] Revoke request failed (token may already be invalid): {}", e.getMessage());
        }
    }

    public Map<String, Object> statusMap() {
        boolean configured = isOAuthConfigured();
        StoredGoogleTokens t = configured ? loadTokens().orElse(null) : null;
        Set<String> enabled = t != null ? t.enabledIntegrations : Set.of();

        Map<String, Object> integrations = new LinkedHashMap<>();
        for (String id : INTEGRATION_IDS) {
            integrations.put(id, Map.of(
                    "id", id,
                    "connected", enabled.contains(id)));
        }
        return Map.of(
                "configured", configured,
                "redirectUriHint", effectiveRedirectUri,
                "integrations", integrations);
    }

    public Path tokenFilePath() {
        return Path.of(System.getProperty("user.home"), "mins_bot_data", "google_integrations", "tokens.json");
    }

    public synchronized java.util.Optional<StoredGoogleTokens> loadTokens() {
        Path p = tokenFilePath();
        if (!Files.isRegularFile(p)) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(objectMapper.readValue(p.toFile(), StoredGoogleTokens.class));
        } catch (Exception e) {
            log.warn("[GoogleOAuth] Could not read token file: {}", e.getMessage());
            return java.util.Optional.empty();
        }
    }

    private synchronized void saveTokens(StoredGoogleTokens tokens) throws IOException {
        Path p = tokenFilePath();
        Files.createDirectories(p.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(p.toFile(), tokens);
    }

    private record PendingState(String integrationId, Instant expires) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StoredGoogleTokens {
        public String refreshToken;
        public String accessToken;
        public long accessTokenExpiryEpochMs;
        public LinkedHashSet<String> enabledIntegrations = new LinkedHashSet<>();
    }
}
