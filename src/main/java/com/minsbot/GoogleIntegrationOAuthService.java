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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-integration Google OAuth supporting MULTIPLE Google accounts.
 * Each account is identified by email; each account holds its own refresh token and
 * the set of integrations (Gmail, Calendar, Drive, ...) it is connected for.
 * Tokens stored under {@code ~/mins_bot_data/mins_google_integrations/tokens.json}.
 */
@Service
public class GoogleIntegrationOAuthService {

    private static final Logger log = LoggerFactory.getLogger(GoogleIntegrationOAuthService.class);

    public static final List<String> INTEGRATION_IDS = List.of(
            "analytics", "gmail", "calendar", "drive", "maps", "workspace", "youtube",
            "googleads", "searchconsole");

    private static final String AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String REVOKE_URL = "https://oauth2.googleapis.com/revoke";

    private static final Map<String, List<String>> SCOPES_BY_INTEGRATION = Map.ofEntries(
            Map.entry("analytics", List.of("https://www.googleapis.com/auth/analytics.readonly")),
            Map.entry("gmail", List.of(
                    "https://www.googleapis.com/auth/gmail.readonly",
                    "https://www.googleapis.com/auth/gmail.send")),
            Map.entry("calendar", List.of(
                    "https://www.googleapis.com/auth/calendar.events",
                    "https://www.googleapis.com/auth/calendar.readonly")),
            Map.entry("drive", List.of("https://www.googleapis.com/auth/drive.readonly")),
            Map.entry("maps", List.of(
                    "openid",
                    "https://www.googleapis.com/auth/userinfo.email",
                    "https://www.googleapis.com/auth/userinfo.profile")),
            Map.entry("workspace", List.of(
                    "openid",
                    "https://www.googleapis.com/auth/userinfo.email",
                    "https://www.googleapis.com/auth/userinfo.profile",
                    "https://www.googleapis.com/auth/contacts.readonly")),
            Map.entry("youtube", List.of(
                    "https://www.googleapis.com/auth/youtube.readonly")),
            Map.entry("googleads", List.of(
                    "https://www.googleapis.com/auth/adwords")),
            Map.entry("searchconsole", List.of(
                    "https://www.googleapis.com/auth/webmasters.readonly"))
    );

    /** Always include userinfo.email so we can identify which Google account granted the tokens. */
    private static final List<String> EMAIL_SCOPES = List.of(
            "openid",
            "https://www.googleapis.com/auth/userinfo.email",
            "https://www.googleapis.com/auth/userinfo.profile");

    private final GoogleIntegrationConfig.GoogleIntegrationProperties properties;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final String effectiveRedirectUri;
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
        if (!springGoogleClientId.isBlank()) return springGoogleClientId;
        return properties.getClientId() != null ? properties.getClientId().strip() : "";
    }

    private String effectiveClientSecret() {
        if (!springGoogleClientSecret.isBlank()) return springGoogleClientSecret;
        return properties.getClientSecret() != null ? properties.getClientSecret().strip() : "";
    }

    public String getEffectiveRedirectUri() { return effectiveRedirectUri; }

    public boolean isOAuthConfigured() {
        return !effectiveClientId().isBlank() && !effectiveClientSecret().isBlank();
    }

    // ═══ OAuth flow ═══

    public URI buildAuthorizationUri(String integrationId) {
        if (!isOAuthConfigured()) {
            throw new IllegalStateException("Google OAuth client ID and secret are not set (Setup tab).");
        }
        if (!SCOPES_BY_INTEGRATION.containsKey(integrationId)) {
            throw new IllegalArgumentException("Unknown integration: " + integrationId);
        }
        // Always include email scopes so we can identify which account granted it
        LinkedHashSet<String> scopes = new LinkedHashSet<>(EMAIL_SCOPES);
        scopes.addAll(SCOPES_BY_INTEGRATION.getOrDefault(integrationId, List.of()));

        String state = UUID.randomUUID().toString().replace("-", "");
        pendingByState.put(state, new PendingState(integrationId, Instant.now().plusSeconds(600)));

        return UriComponentsBuilder.fromUriString(AUTH_URL)
                .queryParam("client_id", effectiveClientId())
                .queryParam("redirect_uri", effectiveRedirectUri)
                .queryParam("response_type", "code")
                .queryParam("scope", String.join(" ", scopes))
                .queryParam("access_type", "offline")
                // select_account + consent → user can pick/add a different Google account and get a refresh token
                .queryParam("prompt", "select_account consent")
                .queryParam("include_granted_scopes", "true")
                .queryParam("state", state)
                .encode(StandardCharsets.UTF_8)
                .build()
                .toUri();
    }

    public String consumeAndValidateState(String state) {
        if (state == null || state.isBlank()) return null;
        PendingState p = pendingByState.remove(state);
        if (p == null || Instant.now().isAfter(p.expires())) return null;
        return p.integrationId();
    }

    public void exchangeCodeAndStore(String integrationId, String code) throws IOException {
        if (!isOAuthConfigured()) throw new IllegalStateException("OAuth not configured");

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", effectiveClientId());
        form.add("client_secret", effectiveClientSecret());
        form.add("redirect_uri", effectiveRedirectUri);
        form.add("grant_type", "authorization_code");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = restTemplate.postForObject(TOKEN_URL,
                new HttpEntity<>(form, headers), Map.class);
        if (body == null || body.get("access_token") == null) {
            throw new IOException("Token response missing access_token");
        }

        String accessToken = String.valueOf(body.get("access_token"));
        Number exp = (Number) body.get("expires_in");
        long expiry = System.currentTimeMillis() + (exp != null ? exp.longValue() : 3600L) * 1000L;
        String refreshToken = body.get("refresh_token") != null
                ? String.valueOf(body.get("refresh_token")) : null;

        // Identify which Google account this is
        String[] userInfo = fetchUserInfo(accessToken);
        String email = userInfo[0];
        String name = userInfo[1];
        if (email == null || email.isBlank()) {
            throw new IOException("Could not fetch account email (userinfo scope missing?)");
        }

        synchronized (this) {
            StoredAccountsFile file = loadAccountsFile();
            StoredAccount account = findOrCreateAccount(file, email);
            account.accountEmail = email;
            if (name != null) account.accountName = name;
            account.accessToken = accessToken;
            account.accessTokenExpiryEpochMs = expiry;
            if (refreshToken != null && !refreshToken.isBlank()) {
                account.refreshToken = refreshToken;
            }
            account.enabledIntegrations.add(integrationId);
            saveAccountsFile(file);
        }

        log.info("[GoogleOAuth] Connected {} for account {}", integrationId, email);
    }

    /**
     * Disconnect a specific account from an integration.
     * When email is null, disconnects ALL accounts from that integration (legacy behavior).
     */
    public void disconnect(String integrationId, String email) throws IOException {
        if (!SCOPES_BY_INTEGRATION.containsKey(integrationId)) {
            throw new IllegalArgumentException("Unknown integration: " + integrationId);
        }
        synchronized (this) {
            StoredAccountsFile file = loadAccountsFile();
            if (file.accounts.isEmpty()) return;

            List<StoredAccount> affected = new ArrayList<>();
            for (StoredAccount a : file.accounts) {
                if (email == null || email.equalsIgnoreCase(a.accountEmail)) {
                    if (a.enabledIntegrations.remove(integrationId)) affected.add(a);
                }
            }

            // Revoke refresh tokens for accounts with nothing left
            file.accounts.removeIf(a -> {
                if (a.enabledIntegrations.isEmpty()) {
                    revokeSilently(a.refreshToken);
                    log.info("[GoogleOAuth] Removed account {} (no integrations left)", a.accountEmail);
                    return true;
                }
                return false;
            });

            if (file.accounts.isEmpty()) {
                Files.deleteIfExists(tokenFilePath());
                log.info("[GoogleOAuth] Token file removed — no accounts left");
            } else {
                saveAccountsFile(file);
            }

            log.info("[GoogleOAuth] Disconnected {} from {} account(s){}",
                    integrationId, affected.size(),
                    email != null ? " (email=" + email + ")" : "");
        }
    }

    /** Legacy single-arg disconnect — disconnects all accounts from the integration. */
    public void disconnect(String integrationId) throws IOException {
        disconnect(integrationId, null);
    }

    private void revokeSilently(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) return;
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("token", refreshToken);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            restTemplate.postForEntity(REVOKE_URL, new HttpEntity<>(form, headers), String.class);
        } catch (Exception e) {
            log.debug("[GoogleOAuth] Revoke failed (token may already be invalid): {}", e.getMessage());
        }
    }

    // ═══ Status ═══

    public Map<String, Object> statusMap() {
        boolean configured = isOAuthConfigured();
        StoredAccountsFile file = configured ? loadAccountsFile() : new StoredAccountsFile();

        Map<String, Object> integrations = new LinkedHashMap<>();
        for (String id : INTEGRATION_IDS) {
            List<Map<String, Object>> accounts = new ArrayList<>();
            for (StoredAccount a : file.accounts) {
                if (!a.enabledIntegrations.contains(id)) continue;
                Map<String, Object> acct = new LinkedHashMap<>();
                acct.put("email", a.accountEmail != null ? a.accountEmail : "");
                acct.put("name", a.accountName != null ? a.accountName : "");
                // Health check: can we actually refresh?
                boolean healthy = getValidAccessTokenForAccount(a) != null;
                acct.put("healthy", healthy);
                if (!healthy) acct.put("healthReason", "Saved tokens cannot be refreshed — disconnect and reconnect.");
                accounts.add(acct);
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", id);
            entry.put("connected", !accounts.isEmpty());
            entry.put("accounts", accounts);
            integrations.put(id, entry);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", configured);
        out.put("redirectUriHint", effectiveRedirectUri);
        out.put("integrations", integrations);
        // Total connected accounts (unique by email)
        out.put("accountCount", file.accounts.size());
        return out;
    }

    // ═══ Token retrieval ═══

    /**
     * Returns a valid access token for ANY connected account with the given integration.
     * Tools that don't need a specific account just call this (single-account compatible).
     */
    public String getValidAccessToken(String integrationId) {
        StoredAccountsFile file = loadAccountsFile();
        for (StoredAccount a : file.accounts) {
            if (a.enabledIntegrations.contains(integrationId)) {
                String tok = getValidAccessTokenForAccount(a);
                if (tok != null) return tok;
            }
        }
        return null;
    }

    /** Returns a valid access token for a specific account (by email). */
    public String getValidAccessToken(String integrationId, String accountEmail) {
        StoredAccountsFile file = loadAccountsFile();
        for (StoredAccount a : file.accounts) {
            if (accountEmail.equalsIgnoreCase(a.accountEmail)
                    && a.enabledIntegrations.contains(integrationId)) {
                return getValidAccessTokenForAccount(a);
            }
        }
        return null;
    }

    /**
     * Returns all valid access tokens for accounts connected to the given integration,
     * keyed by account email. Useful for tools that aggregate data across accounts
     * (e.g. "list unread emails across all Gmail accounts").
     */
    public Map<String, String> getAllValidAccessTokens(String integrationId) {
        StoredAccountsFile file = loadAccountsFile();
        Map<String, String> out = new LinkedHashMap<>();
        for (StoredAccount a : file.accounts) {
            if (a.enabledIntegrations.contains(integrationId)) {
                String tok = getValidAccessTokenForAccount(a);
                if (tok != null && a.accountEmail != null) {
                    out.put(a.accountEmail, tok);
                }
            }
        }
        return out;
    }

    public boolean isConnected(String integrationId) {
        StoredAccountsFile file = loadAccountsFile();
        for (StoredAccount a : file.accounts) {
            if (a.enabledIntegrations.contains(integrationId)) return true;
        }
        return false;
    }

    private synchronized String getValidAccessTokenForAccount(StoredAccount a) {
        if (a.accessToken != null && System.currentTimeMillis() < a.accessTokenExpiryEpochMs - 60_000) {
            return a.accessToken;
        }
        if (a.refreshToken == null || a.refreshToken.isBlank()) {
            log.warn("[GoogleOAuth] No refresh token for account {}", a.accountEmail);
            return null;
        }
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("client_id", effectiveClientId());
            form.add("client_secret", effectiveClientSecret());
            form.add("refresh_token", a.refreshToken);
            form.add("grant_type", "refresh_token");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = restTemplate.postForObject(TOKEN_URL,
                    new HttpEntity<>(form, headers), Map.class);
            if (body == null || body.get("access_token") == null) return null;

            a.accessToken = String.valueOf(body.get("access_token"));
            Number exp = (Number) body.get("expires_in");
            long sec = exp != null ? exp.longValue() : 3600L;
            a.accessTokenExpiryEpochMs = System.currentTimeMillis() + sec * 1000L;
            // Persist refreshed token
            StoredAccountsFile file = loadAccountsFile();
            for (StoredAccount stored : file.accounts) {
                if (stored.accountEmail != null && stored.accountEmail.equalsIgnoreCase(a.accountEmail)) {
                    stored.accessToken = a.accessToken;
                    stored.accessTokenExpiryEpochMs = a.accessTokenExpiryEpochMs;
                    break;
                }
            }
            try { saveAccountsFile(file); } catch (IOException ignored) {}

            log.info("[GoogleOAuth] Refreshed access token for {}", a.accountEmail);
            return a.accessToken;
        } catch (Exception e) {
            log.warn("[GoogleOAuth] Refresh failed for {}: {}", a.accountEmail, e.getMessage());
            return null;
        }
    }

    // ═══ Userinfo ═══

    /** Returns [email, name] from Google's userinfo endpoint, or [null, null] on failure. */
    @SuppressWarnings("unchecked")
    private String[] fetchUserInfo(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + accessToken);
        try {
            Map<String, Object> info = restTemplate.exchange(
                    "https://www.googleapis.com/oauth2/v2/userinfo",
                    org.springframework.http.HttpMethod.GET,
                    new HttpEntity<>(headers),
                    Map.class
            ).getBody();
            if (info != null) {
                String email = info.get("email") != null ? String.valueOf(info.get("email")) : null;
                String name = info.get("name") != null ? String.valueOf(info.get("name")) : null;
                return new String[]{email, name};
            }
        } catch (Exception e) {
            log.debug("[GoogleOAuth] userinfo failed: {}", e.getMessage());
        }
        return new String[]{null, null};
    }

    // ═══ Storage ═══

    public Path tokenFilePath() {
        return Path.of(System.getProperty("user.home"), "mins_bot_data", "mins_google_integrations", "tokens.json");
    }

    /**
     * Load the accounts file, auto-migrating from the old single-account format if needed.
     * Returns an empty file if nothing exists or parsing fails.
     */
    public synchronized StoredAccountsFile loadAccountsFile() {
        Path p = tokenFilePath();
        if (!Files.isRegularFile(p)) return new StoredAccountsFile();
        try {
            String json = Files.readString(p);
            // New format has "accounts" at the root
            if (json.contains("\"accounts\"")) {
                return objectMapper.readValue(json, StoredAccountsFile.class);
            }
            // Legacy format: single { refreshToken, accessToken, enabledIntegrations, accountEmail }
            StoredAccount legacy = objectMapper.readValue(json, StoredAccount.class);
            StoredAccountsFile migrated = new StoredAccountsFile();
            if (legacy.refreshToken != null || !legacy.enabledIntegrations.isEmpty()) {
                migrated.accounts.add(legacy);
                log.info("[GoogleOAuth] Migrated legacy single-account token file → multi-account");
                try { saveAccountsFile(migrated); } catch (IOException ignored) {}
            }
            return migrated;
        } catch (Exception e) {
            log.warn("[GoogleOAuth] Could not read token file: {}", e.getMessage());
            return new StoredAccountsFile();
        }
    }

    /** Legacy-compatible helper — returns the first account if any, so old callers still work. */
    public synchronized Optional<StoredAccount> loadTokens() {
        StoredAccountsFile f = loadAccountsFile();
        return f.accounts.isEmpty() ? Optional.empty() : Optional.of(f.accounts.get(0));
    }

    private synchronized void saveAccountsFile(StoredAccountsFile file) throws IOException {
        Path p = tokenFilePath();
        Files.createDirectories(p.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(p.toFile(), file);
    }

    private StoredAccount findOrCreateAccount(StoredAccountsFile file, String email) {
        for (StoredAccount a : file.accounts) {
            if (email.equalsIgnoreCase(a.accountEmail)) return a;
        }
        StoredAccount a = new StoredAccount();
        a.accountEmail = email;
        file.accounts.add(a);
        return a;
    }

    // ═══ Data classes ═══

    private record PendingState(String integrationId, Instant expires) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StoredAccountsFile {
        public List<StoredAccount> accounts = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StoredAccount {
        public String accountEmail;
        public String accountName;
        public String refreshToken;
        public String accessToken;
        public long accessTokenExpiryEpochMs;
        public LinkedHashSet<String> enabledIntegrations = new LinkedHashSet<>();
    }

    /** Legacy alias so external code referencing StoredGoogleTokens still compiles. */
    public static class StoredGoogleTokens extends StoredAccount {}
}
