package com.minsbot;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Properties;

@Service
public class SetupSecretsService {

    private static final String SECRETS_FILENAME = "application-secrets.properties";

    private final Environment environment;

    public SetupSecretsService(Environment environment) {
        this.environment = environment;
    }

    public Path secretsFilePath() {
        return Paths.get(System.getProperty("user.dir", ".")).resolve(SECRETS_FILENAME).toAbsolutePath().normalize();
    }

    public Map<String, Object> buildSetupPayload() {
        List<Map<String, Object>> groupsOut = new java.util.ArrayList<>();
        for (SetupSecretsRegistry.SetupGroup g : SetupSecretsRegistry.groups()) {
            List<Map<String, Object>> fields = new java.util.ArrayList<>();
            for (SetupSecretsRegistry.SetupField f : g.fields()) {
                String v = environment.getProperty(f.propertyKey());
                boolean configured = v != null && !v.isBlank();
                // For masked (secret) fields: show first 10 chars + asterisks so the user can
                // identify which value is saved without revealing the full secret.
                // For non-mask fields: show the full value (they're not secrets — IDs, endpoints, etc.).
                String preview = "";
                if (configured && v != null) {
                    String s = v.trim();
                    if (f.mask()) {
                        int shown = Math.min(10, s.length());
                        int hidden = Math.max(0, s.length() - shown);
                        preview = s.substring(0, shown) + "*".repeat(Math.min(hidden, 20));
                    } else {
                        preview = s;
                    }
                }
                Map<String, Object> field = new java.util.LinkedHashMap<>();
                field.put("key", f.propertyKey());
                field.put("label", f.label());
                field.put("mask", f.mask());
                field.put("configured", configured);
                field.put("preview", preview);
                fields.add(field);
            }
            groupsOut.add(Map.of(
                    "id", g.id(),
                    "title", g.title(),
                    "fields", fields
            ));
        }
        return Map.of(
                "secretsFile", secretsFilePath().toString(),
                "groups", groupsOut
        );
    }

    /**
     * Applies sets and unsets. Blank values in {@code set} are ignored. Unknown keys are rejected.
     */
    public void applyUpdates(Map<String, String> set, List<String> unset) throws IOException {
        Path path = secretsFilePath();
        Properties props = loadProperties(path);

        if (unset != null) {
            for (String k : unset) {
                if (k == null || k.isBlank()) continue;
                if (!SetupSecretsRegistry.isAllowedKey(k)) {
                    throw new IllegalArgumentException("Unknown property key: " + k);
                }
                props.remove(k);
            }
        }

        if (set != null) {
            for (Map.Entry<String, String> e : set.entrySet()) {
                String k = e.getKey();
                String v = e.getValue();
                if (k == null || k.isBlank()) continue;
                if (!SetupSecretsRegistry.isAllowedKey(k)) {
                    throw new IllegalArgumentException("Unknown property key: " + k);
                }
                if (v == null) continue;
                String t = v.trim();
                if (t.isEmpty()) continue;
                props.setProperty(k, t);
            }
        }

        Files.createDirectories(path.getParent());
        try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            props.store(w, "Mins Bot secrets (Setup tab). Restart the app for some keys to take effect.");
        }
    }

    private static Properties loadProperties(Path path) throws IOException {
        Properties p = new Properties();
        if (Files.isRegularFile(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                p.load(in);
            }
        }
        return p;
    }
}
