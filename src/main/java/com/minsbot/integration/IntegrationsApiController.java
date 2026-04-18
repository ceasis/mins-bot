package com.minsbot.integration;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST API for managing integrations: list, get, configure credentials, call.
 * Separate from the existing GoogleIntegrationsController (which handles the
 * Google-specific OAuth dance) and the per-platform webhook controllers.
 */
@RestController
@RequestMapping("/api/integrations-api")
public class IntegrationsApiController {

    private final IntegrationRegistry registry;
    private final IntegrationCredentialStore credentials;
    private final IntegrationCallService caller;

    public IntegrationsApiController(IntegrationRegistry registry,
                                     IntegrationCredentialStore credentials,
                                     IntegrationCallService caller) {
        this.registry = registry;
        this.credentials = credentials;
        this.caller = caller;
    }

    @GetMapping
    public ResponseEntity<?> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (IntegrationRegistry.Integration i : registry.all()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", i.id());
            entry.put("name", i.name());
            entry.put("category", i.category());
            entry.put("authType", i.authType().name());
            entry.put("baseUrl", i.baseUrl());
            entry.put("docsUrl", i.docsUrl());
            entry.put("configured", credentials.isConfigured(i.id()));
            out.add(entry);
        }
        return ResponseEntity.ok(Map.of("total", registry.size(), "integrations", out));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        return registry.get(id)
                .<ResponseEntity<?>>map(i -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", i.id());
                    entry.put("name", i.name());
                    entry.put("category", i.category());
                    entry.put("authType", i.authType().name());
                    entry.put("baseUrl", i.baseUrl());
                    entry.put("authHeader", i.authHeader());
                    entry.put("docsUrl", i.docsUrl());
                    entry.put("propertyPrefix", i.propertyPrefix());
                    entry.put("configured", credentials.isConfigured(i.id()));
                    entry.put("configuredKeys", credentials.getCredentials(i.id()).keySet());
                    return ResponseEntity.ok(entry);
                })
                .orElse(ResponseEntity.status(404).body(Map.of("error", "unknown integration: " + id)));
    }

    @PostMapping("/{id}/credentials")
    public ResponseEntity<?> setCredentials(@PathVariable String id, @RequestBody Map<String, String> creds) {
        if (registry.get(id).isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "unknown integration: " + id));
        if (creds == null || creds.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "credentials map required"));
        credentials.setCredentials(id, creds);
        return ResponseEntity.ok(Map.of("id", id, "configured", true, "keys", creds.keySet()));
    }

    @DeleteMapping("/{id}/credentials")
    public ResponseEntity<?> clearCredentials(@PathVariable String id) {
        if (registry.get(id).isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "unknown integration: " + id));
        credentials.removeCredentials(id);
        return ResponseEntity.ok(Map.of("id", id, "configured", false));
    }

    @PostMapping("/{id}/call")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> call(@PathVariable String id, @RequestBody Map<String, Object> body) {
        String method = (String) body.getOrDefault("method", "GET");
        String path = (String) body.getOrDefault("path", "/");
        Map<String, String> headers = (Map<String, String>) body.get("headers");
        String reqBody = (String) body.get("body");
        try {
            return ResponseEntity.ok(caller.call(id, method, path, headers, reqBody));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/categories")
    public ResponseEntity<?> categories() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String cat : registry.categories()) {
            out.put(cat, registry.byCategory(cat).stream().map(IntegrationRegistry.Integration::id).toList());
        }
        return ResponseEntity.ok(out);
    }
}
