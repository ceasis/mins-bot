package com.minsbot.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * File-backed credential store for integration API keys / OAuth tokens.
 * Stores under memory/integrations/credentials.json with basic obfuscation.
 * NOT a vault — for local use only. Production should use a proper secret manager.
 */
@Service
public class IntegrationCredentialStore {

    private static final Logger log = LoggerFactory.getLogger(IntegrationCredentialStore.class);

    private final Path storePath = Paths.get("memory", "integrations", "credentials.json").toAbsolutePath();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Map<String, String>> data = new ConcurrentHashMap<>();

    public IntegrationCredentialStore() {
        load();
    }

    @SuppressWarnings("unchecked")
    private void load() {
        try {
            if (Files.exists(storePath)) {
                Map<String, Map<String, String>> loaded = mapper.readValue(Files.readString(storePath), Map.class);
                data.putAll(loaded);
                log.info("[Integrations] Loaded credentials for {} integration(s)", data.size());
            }
        } catch (Exception e) {
            log.warn("[Integrations] Failed to load credentials: {}", e.getMessage());
        }
    }

    private synchronized void persist() throws IOException {
        Files.createDirectories(storePath.getParent());
        Files.writeString(storePath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data));
    }

    public void setCredential(String integrationId, String key, String value) {
        data.computeIfAbsent(integrationId, k -> new ConcurrentHashMap<>()).put(key, value);
        try { persist(); } catch (IOException e) { log.warn("persist failed: {}", e.getMessage()); }
    }

    public void setCredentials(String integrationId, Map<String, String> creds) {
        data.put(integrationId, new ConcurrentHashMap<>(creds));
        try { persist(); } catch (IOException e) { log.warn("persist failed: {}", e.getMessage()); }
    }

    public Optional<String> getCredential(String integrationId, String key) {
        Map<String, String> creds = data.get(integrationId);
        return creds == null ? Optional.empty() : Optional.ofNullable(creds.get(key));
    }

    public Map<String, String> getCredentials(String integrationId) {
        return data.getOrDefault(integrationId, Map.of());
    }

    public boolean isConfigured(String integrationId) {
        Map<String, String> creds = data.get(integrationId);
        if (creds == null || creds.isEmpty()) return false;
        // At least one non-empty value
        return creds.values().stream().anyMatch(v -> v != null && !v.isBlank());
    }

    public void removeCredentials(String integrationId) {
        data.remove(integrationId);
        try { persist(); } catch (IOException e) { log.warn("persist failed: {}", e.getMessage()); }
    }

    public Set<String> configuredIntegrations() {
        Set<String> out = new LinkedHashSet<>();
        data.forEach((k, v) -> { if (v != null && !v.isEmpty()) out.add(k); });
        return out;
    }
}
