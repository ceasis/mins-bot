package com.minsbot;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

@RestController
@RequestMapping("/api/integrations/google")
public class GoogleIntegrationsController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GoogleIntegrationsController.class);

    private final GoogleIntegrationOAuthService oauthService;

    public GoogleIntegrationsController(GoogleIntegrationOAuthService oauthService) {
        this.oauthService = oauthService;
    }

    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> status() {
        return oauthService.statusMap();
    }

    /**
     * Starts the browser OAuth flow (redirect to Google). On failure, redirects home with query params (no HTML error page).
     */
    @GetMapping("/authorize")
    public ResponseEntity<Void> authorize(@RequestParam("integration") String integration) {
        try {
            URI uri = oauthService.buildAuthorizationUri(integration.strip().toLowerCase());
            return ResponseEntity.status(HttpStatus.FOUND).location(uri).build();
        } catch (IllegalArgumentException e) {
            return redirect("/?google_oauth=error&reason=invalid_integration");
        } catch (IllegalStateException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.startsWith("Already connected")) {
                return redirect("/?google_oauth=info&reason=already_connected");
            }
            if (msg.contains("not set")) {
                return redirect("/?google_oauth=error&reason=not_configured");
            }
            return redirect("/?google_oauth=error&reason=oauth_unavailable");
        }
    }

    @GetMapping("/oauth2/callback")
    public ResponseEntity<Void> oauthCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error) {
        if (error != null && !error.isBlank()) {
            return redirect("/?google_oauth=error&reason=" + encode(error));
        }
        if (code == null || code.isBlank() || state == null || state.isBlank()) {
            return redirect("/?google_oauth=error&reason=missing_params");
        }
        String integrationId = oauthService.consumeAndValidateState(state);
        if (integrationId == null) {
            return redirect("/?google_oauth=error&reason=bad_state");
        }
        try {
            oauthService.exchangeCodeAndStore(integrationId, code);
            return redirect("/?google_oauth=ok&integration=" + encode(integrationId));
        } catch (Exception e) {
            log.warn("[GoogleOAuth] Callback failed: {}", e.getMessage());
            return redirect("/?google_oauth=error&reason=token_exchange");
        }
    }

    private static String encode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
    }

    @PostMapping(value = "/disconnect", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> disconnect(@RequestBody Map<String, String> body) {
        String integration = body != null ? body.get("integration") : null;
        if (integration == null || integration.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "integration is required");
        }
        try {
            oauthService.disconnect(integration.strip().toLowerCase());
            return Map.of("status", "ok");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }
}
