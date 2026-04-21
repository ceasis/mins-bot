package com.minsbot.offline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single source of truth for offline mode. When enabled, all outbound cloud calls
 * must self-gate via {@link #isOffline()} before hitting the network. Core selling
 * point for regulated verticals (legal/finance/healthcare) that need a hard
 * "nothing leaves this machine" guarantee.
 *
 * <p>State persists to {@code memory/offline-mode.flag} so it survives restarts.</p>
 *
 * <p>What gets blocked when offline:</p>
 * <ul>
 *   <li>Cloud LLM providers (OpenAI, Anthropic, Gemini)</li>
 *   <li>Vision APIs (GPT-4V, Gemini Vision)</li>
 *   <li>Web search (Serper, SerpAPI, DuckDuckGo)</li>
 *   <li>Third-party integration REST calls (Stripe, Notion, GitHub, etc.)</li>
 *   <li>HIBP, NVD CVE, public-IP lookups, sitemap checks, etc.</li>
 * </ul>
 *
 * <p>What still works offline:</p>
 * <ul>
 *   <li>Local Ollama models (if installed)</li>
 *   <li>All on-device skills (hashcalc, regextester, csvtools, 80+ others)</li>
 *   <li>Clipboard, timers, notes, reminders, file ops</li>
 *   <li>Local OCR/vision fallbacks (Textract, built-in)</li>
 *   <li>Local speech-to-text / TTS (Windows SAPI)</li>
 * </ul>
 */
@Service
public class OfflineModeService {

    private static final Logger log = LoggerFactory.getLogger(OfflineModeService.class);
    private static final Path FLAG = Paths.get("memory", "offline-mode.flag").toAbsolutePath();

    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private volatile Instant enabledAt;

    public OfflineModeService() {
        load();
    }

    private void load() {
        try {
            if (Files.exists(FLAG)) {
                String content = Files.readString(FLAG).trim();
                if ("1".equals(content) || "true".equalsIgnoreCase(content)) {
                    enabled.set(true);
                    enabledAt = Files.getLastModifiedTime(FLAG).toInstant();
                    log.info("[OfflineMode] LOADED — offline mode is ON (since {})", enabledAt);
                }
            }
        } catch (Exception e) {
            log.warn("[OfflineMode] failed to load flag: {}", e.getMessage());
        }
    }

    public boolean isOffline() { return enabled.get(); }
    public boolean isOnline() { return !enabled.get(); }
    public Instant enabledAt() { return enabledAt; }

    public synchronized void enable() {
        if (enabled.compareAndSet(false, true)) {
            enabledAt = Instant.now();
            persist("1");
            log.warn("[OfflineMode] ENABLED — all cloud APIs blocked. Local only.");
        }
    }

    public synchronized void disable() {
        if (enabled.compareAndSet(true, false)) {
            enabledAt = null;
            persist("0");
            log.info("[OfflineMode] DISABLED — cloud APIs re-enabled.");
        }
    }

    public boolean toggle() {
        if (enabled.get()) { disable(); return false; }
        enable(); return true;
    }

    /**
     * Throws {@link OfflineModeException} if offline mode is on. Call this at the
     * top of any method that's about to hit a cloud API.
     */
    public void requireOnline(String operation) {
        if (enabled.get()) {
            throw new OfflineModeException(operation);
        }
    }

    /** Non-throwing variant — returns a polite error string or null if online. */
    public String blockReasonOrNull(String operation) {
        if (!enabled.get()) return null;
        return "Offline mode is ON — '" + operation + "' was blocked. Nothing left the machine. "
                + "Toggle via the title-bar shield icon or POST /api/offline-mode/disable.";
    }

    private void persist(String value) {
        try {
            Files.createDirectories(FLAG.getParent());
            Files.writeString(FLAG, value);
        } catch (IOException e) {
            log.warn("[OfflineMode] persist failed: {}", e.getMessage());
        }
    }

    public static class OfflineModeException extends RuntimeException {
        public OfflineModeException(String operation) {
            super("Offline mode is ON — blocked: " + operation);
        }
    }
}
