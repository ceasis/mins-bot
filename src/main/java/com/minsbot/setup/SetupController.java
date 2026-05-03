package com.minsbot.setup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Self-setup flow. The bot detects which API keys it's missing and offers
 * the user a modal to fill them in. The user can defer ("Later") and the
 * modal won't show again until the deferral expires.
 *
 * <p>Storage: writes to {@code application-secrets.properties} at project
 * root (the same file the rest of the app reads at startup). Saved keys
 * don't take effect until the next restart — surfaced in the UI.</p>
 *
 * <p>Defer marker: {@code ~/mins_bot_data/setup_skipped_until.txt} with
 * epoch-ms.</p>
 */
@RestController
@RequestMapping("/api/setup")
public class SetupController {

    private static final Logger log = LoggerFactory.getLogger(SetupController.class);
    private static final Path SECRETS = Paths.get("application-secrets.properties");
    private static final Path SKIP_FILE =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "setup_skipped_until.txt");

    /** Catalogue of keys the bot can ask for. Order = display order in the modal. */
    private static final List<NeedDef> CATALOGUE = List.of(
            // ── Quick-Setup essentials (LLMs the bot needs to actually function) ──
            new NeedDef("spring.ai.openai.api-key", "OPENAI_API_KEY",
                    "OpenAI API key",
                    "Powers tool-calling and audio transcription. Most-used provider.",
                    "https://platform.openai.com/api-keys", true, true),
            new NeedDef("app.anthropic.api-key", "ANTHROPIC_API_KEY",
                    "Anthropic / Claude API key",
                    "Used by the SpecialCodeGenerator and as a fallback chat backend.",
                    "https://console.anthropic.com/settings/keys", false, true),
            new NeedDef("gemini.api.key", "GEMINI_API_KEY",
                    "Google Gemini API key",
                    "Optional — enables Gemini-backed image and chat tools.",
                    "https://aistudio.google.com/app/apikey", false, true),
            // ── Optional add-ons (configurable from the full Setup tab, not the modal) ──
            new NeedDef("app.groq.api-key", "GROQ_API_KEY",
                    "Groq API key",
                    "Optional — fast Llama / Mixtral inference for cheap routing.",
                    "https://console.groq.com/keys", false, false),
            new NeedDef("app.elevenlabs.api-key", "ELEVENLABS_API_KEY",
                    "ElevenLabs API key",
                    "Optional — premium TTS voices. Bot falls back to local Piper otherwise.",
                    "https://elevenlabs.io/app/settings/api-keys", false, false),
            new NeedDef("fish.audio.api.key", "FISH_AUDIO_API_KEY",
                    "Fish Audio API key",
                    "Optional — alternative premium TTS provider.",
                    "https://fish.audio/", false, false),
            new NeedDef("app.github.token", "GITHUB_TOKEN",
                    "GitHub token",
                    "Enables creating repos / pushing generated code projects.",
                    "https://github.com/settings/tokens", false, false)
    );

    @GetMapping(value = "/needs", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> needs() {
        Properties existing = loadSecrets();
        long skippedUntil = readSkippedUntil();
        boolean suppress = System.currentTimeMillis() < skippedUntil;
        List<Map<String, Object>> needs = new ArrayList<>();
        for (NeedDef d : CATALOGUE) {
            if (hasValue(existing, d)) continue;
            // Quick-setup modal only shows the essentials (LLMs); everything else
            // is configurable from the full Setup tab.
            if (!d.quickSetup) continue;
            needs.add(Map.of(
                    "key", d.propKey,
                    "envKey", d.envKey,
                    "label", d.label,
                    "hint", d.hint,
                    "docs", d.docs,
                    "required", d.required
            ));
        }
        return Map.of(
                "needs", needs,
                "skippedUntil", skippedUntil,
                "suppressUntilSkipExpires", suppress,
                "secretsFile", SECRETS.toAbsolutePath().toString()
        );
    }

    @PostMapping(value = "/save", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> save(@RequestBody Map<String, String> body) {
        if (body == null || body.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "no values provided"));
        }
        // Validate keys against catalogue — refuse arbitrary writes.
        Set<String> known = new HashSet<>();
        for (NeedDef d : CATALOGUE) known.add(d.propKey);
        for (String k : body.keySet()) {
            if (!known.contains(k)) {
                return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "unknown key: " + k));
            }
        }
        try {
            int written = writeSecrets(body);
            // Saving any value resets the skip timer — user is engaging.
            try { Files.deleteIfExists(SKIP_FILE); } catch (IOException ignored) {}
            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "saved", written,
                    "note", "Restart the bot for the new keys to take effect."
            ));
        } catch (Exception e) {
            log.warn("[Setup] save failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("ok", false, "error", e.getMessage()));
        }
    }

    @PostMapping(value = "/skip", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> skip(@RequestBody(required = false) Map<String, Object> body) {
        long hours = 6;
        if (body != null && body.get("hours") instanceof Number n) hours = Math.max(1, n.longValue());
        long until = System.currentTimeMillis() + hours * 3600_000L;
        try {
            Files.createDirectories(SKIP_FILE.getParent());
            Files.writeString(SKIP_FILE, Long.toString(until), StandardCharsets.UTF_8);
            return ResponseEntity.ok(Map.of("ok", true, "skippedUntil", until));
        } catch (IOException e) {
            return ResponseEntity.status(500).body(Map.of("ok", false, "error", e.getMessage()));
        }
    }

    // ───────────────────────── helpers ─────────────────────────

    private static boolean hasValue(Properties props, NeedDef d) {
        // Property file value takes priority; env var also counts.
        String v = props.getProperty(d.propKey);
        if (v != null && !v.isBlank()) return true;
        String env = System.getenv(d.envKey);
        return env != null && !env.isBlank();
    }

    private static Properties loadSecrets() {
        Properties p = new Properties();
        if (Files.isRegularFile(SECRETS)) {
            try (var in = Files.newBufferedReader(SECRETS, StandardCharsets.UTF_8)) {
                p.load(in);
            } catch (IOException e) {
                log.debug("[Setup] could not read {}: {}", SECRETS, e.getMessage());
            }
        }
        return p;
    }

    /**
     * Write/replace the supplied keys in {@code application-secrets.properties},
     * preserving existing lines (including comments). Returns the count saved.
     */
    private static int writeSecrets(Map<String, String> updates) throws IOException {
        List<String> lines = Files.isRegularFile(SECRETS)
                ? new ArrayList<>(Files.readAllLines(SECRETS, StandardCharsets.UTF_8))
                : new ArrayList<>();
        Set<String> remaining = new LinkedHashSet<>(updates.keySet());
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            String trimmed = raw.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int eq = trimmed.indexOf('=');
            if (eq <= 0) continue;
            String k = trimmed.substring(0, eq).trim();
            if (updates.containsKey(k)) {
                lines.set(i, k + "=" + nullSafe(updates.get(k)));
                remaining.remove(k);
            }
        }
        // Append any new keys that weren't already in the file.
        if (!remaining.isEmpty()) {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) lines.add("");
            lines.add("# Added by setup modal " + java.time.LocalDateTime.now());
            for (String k : remaining) lines.add(k + "=" + nullSafe(updates.get(k)));
        }
        Files.write(SECRETS, lines, StandardCharsets.UTF_8);
        return updates.size();
    }

    private static String nullSafe(String s) { return s == null ? "" : s.trim(); }

    private static long readSkippedUntil() {
        if (!Files.isRegularFile(SKIP_FILE)) return 0L;
        try {
            return Long.parseLong(Files.readString(SKIP_FILE, StandardCharsets.UTF_8).trim());
        } catch (Exception e) { return 0L; }
    }

    private record NeedDef(String propKey, String envKey, String label, String hint, String docs,
                            boolean required, boolean quickSetup) {
        // Backwards-compat ctor — older entries without an explicit quickSetup flag default it to true
        // when required (so the OpenAI key shows in Quick Setup), false otherwise.
        NeedDef(String propKey, String envKey, String label, String hint, String docs, boolean required) {
            this(propKey, envKey, label, hint, docs, required, required);
        }
    }
}
