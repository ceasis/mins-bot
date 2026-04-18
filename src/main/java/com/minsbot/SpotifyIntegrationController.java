package com.minsbot;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/integrations/spotify")
public class SpotifyIntegrationController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SpotifyIntegrationController.class);

    private final SpotifyOAuthService oauthService;

    public SpotifyIntegrationController(SpotifyOAuthService oauthService) {
        this.oauthService = oauthService;
    }

    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> status() {
        return oauthService.statusMap();
    }

    @GetMapping("/authorize")
    public ResponseEntity<Void> authorize() {
        try {
            URI uri = oauthService.buildAuthorizationUri();
            return ResponseEntity.status(HttpStatus.FOUND).location(uri).build();
        } catch (IllegalStateException e) {
            return redirect("/?spotify_oauth=error&reason=not_configured");
        }
    }

    @GetMapping("/oauth2/callback")
    public ResponseEntity<Void> oauthCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error) {
        if (error != null && !error.isBlank()) {
            return redirect("/?spotify_oauth=error&reason=" + encode(error));
        }
        if (code == null || code.isBlank() || state == null || state.isBlank()) {
            return redirect("/?spotify_oauth=error&reason=missing_params");
        }
        if (!oauthService.validateState(state)) {
            return redirect("/?spotify_oauth=error&reason=bad_state");
        }
        try {
            oauthService.exchangeCodeAndStore(code);
            return redirect("/?spotify_oauth=ok");
        } catch (Exception e) {
            log.warn("[SpotifyOAuth] Callback failed: {}", e.getMessage());
            return redirect("/?spotify_oauth=error&reason=token_exchange");
        }
    }

    @PostMapping(value = "/disconnect", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> disconnect() {
        try {
            oauthService.disconnect();
            return Map.of("status", "ok");
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private static String encode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
    }
}
