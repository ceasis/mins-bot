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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spotify Web API OAuth (Authorization Code flow).
 * Tokens live in {@code ~/mins_bot_data/spotify/tokens.json}. Scopes allow searching
 * tracks and controlling playback (requires Spotify Premium for PUT /me/player/play).
 */
@Service
public class SpotifyOAuthService {

    private static final Logger log = LoggerFactory.getLogger(SpotifyOAuthService.class);

    private static final String AUTH_URL = "https://accounts.spotify.com/authorize";
    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";

    private static final List<String> SCOPES = List.of(
            "user-read-private",
            "user-read-email",
            "user-read-playback-state",
            "user-modify-playback-state",
            "user-read-currently-playing",
            "streaming",
            "playlist-read-private",
            "user-library-read"
    );

    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    private final ConcurrentHashMap<String, Instant> pendingStates = new ConcurrentHashMap<>();

    public SpotifyOAuthService(
            ObjectMapper objectMapper,
            RestTemplate restTemplate,
            @Value("${server.port:8765}") int serverPort,
            @Value("${app.spotify.client-id:}") String clientId,
            @Value("${app.spotify.client-secret:}") String clientSecret,
            @Value("${app.spotify.redirect-uri:}") String configuredRedirectUri) {
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
        this.clientId = clientId != null ? clientId.strip() : "";
        this.clientSecret = clientSecret != null ? clientSecret.strip() : "";
        this.redirectUri = !configuredRedirectUri.isBlank()
                ? configuredRedirectUri.strip()
                : "http://127.0.0.1:" + serverPort + "/api/integrations/spotify/oauth2/callback";
    }

    public boolean isConfigured() {
        return !clientId.isBlank() && !clientSecret.isBlank();
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public URI buildAuthorizationUri() {
        if (!isConfigured()) {
            throw new IllegalStateException("Spotify client ID and secret not set (Setup tab).");
        }
        String state = UUID.randomUUID().toString().replace("-", "");
        pendingStates.put(state, Instant.now().plusSeconds(600));

        return UriComponentsBuilder.fromUriString(AUTH_URL)
                .queryParam("client_id", clientId)
                .queryParam("response_type", "code")
                .queryParam("redirect_uri", redirectUri)
                .queryParam("scope", String.join(" ", SCOPES))
                .queryParam("state", state)
                .queryParam("show_dialog", "false")
                .encode(StandardCharsets.UTF_8)
                .build()
                .toUri();
    }

    public boolean validateState(String state) {
        if (state == null || state.isBlank()) return false;
        Instant expires = pendingStates.remove(state);
        return expires != null && Instant.now().isBefore(expires);
    }

    public void exchangeCodeAndStore(String code) throws IOException {
        if (!isConfigured()) throw new IllegalStateException("OAuth not configured");

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", redirectUri);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set("Authorization", "Basic " + basicAuth());
        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(form, headers);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = restTemplate.postForObject(TOKEN_URL, entity, Map.class);
        if (body == null || body.get("access_token") == null) {
            throw new IOException("Spotify token response missing access_token");
        }

        StoredSpotifyTokens tokens = new StoredSpotifyTokens();
        tokens.accessToken = String.valueOf(body.get("access_token"));
        Number exp = (Number) body.get("expires_in");
        tokens.accessTokenExpiryEpochMs = System.currentTimeMillis()
                + (exp != null ? exp.longValue() : 3600L) * 1000L;
        Object rt = body.get("refresh_token");
        if (rt != null) tokens.refreshToken = String.valueOf(rt);
        tokens.connected = true;
        saveTokens(tokens);
        log.info("[SpotifyOAuth] Connected successfully");
    }

    public synchronized String getValidAccessToken() {
        StoredSpotifyTokens t = loadTokens().orElse(null);
        if (t == null || !t.connected) return null;
        if (t.accessToken != null && System.currentTimeMillis() < t.accessTokenExpiryEpochMs - 60_000) {
            return t.accessToken;
        }
        if (t.refreshToken == null || t.refreshToken.isBlank()) return null;

        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "refresh_token");
            form.add("refresh_token", t.refreshToken);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.set("Authorization", "Basic " + basicAuth());

            @SuppressWarnings("unchecked")
            Map<String, Object> body = restTemplate.postForObject(TOKEN_URL,
                    new HttpEntity<>(form, headers), Map.class);
            if (body == null || body.get("access_token") == null) return null;

            t.accessToken = String.valueOf(body.get("access_token"));
            Number exp = (Number) body.get("expires_in");
            t.accessTokenExpiryEpochMs = System.currentTimeMillis()
                    + (exp != null ? exp.longValue() : 3600L) * 1000L;
            Object newRt = body.get("refresh_token");
            if (newRt != null) t.refreshToken = String.valueOf(newRt);
            saveTokens(t);
            return t.accessToken;
        } catch (Exception e) {
            log.warn("[SpotifyOAuth] Token refresh failed: {}", e.getMessage());
            return null;
        }
    }

    public void disconnect() throws IOException {
        Files.deleteIfExists(tokenFilePath());
        log.info("[SpotifyOAuth] Disconnected, token file deleted");
    }

    public Map<String, Object> statusMap() {
        boolean connected = loadTokens().map(t -> t.connected).orElse(false);
        return Map.of(
                "configured", isConfigured(),
                "connected", connected,
                "redirectUriHint", redirectUri
        );
    }

    private String basicAuth() {
        String creds = clientId + ":" + clientSecret;
        return Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8));
    }

    public Path tokenFilePath() {
        return Path.of(System.getProperty("user.home"), "mins_bot_data", "spotify", "tokens.json");
    }

    public synchronized Optional<StoredSpotifyTokens> loadTokens() {
        Path p = tokenFilePath();
        if (!Files.isRegularFile(p)) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(p.toFile(), StoredSpotifyTokens.class));
        } catch (Exception e) {
            log.warn("[SpotifyOAuth] Could not read token file: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private synchronized void saveTokens(StoredSpotifyTokens tokens) throws IOException {
        Path p = tokenFilePath();
        Files.createDirectories(p.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(p.toFile(), tokens);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StoredSpotifyTokens {
        public String accessToken;
        public String refreshToken;
        public long accessTokenExpiryEpochMs;
        public boolean connected;
    }
}
