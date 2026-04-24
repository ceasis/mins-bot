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

    /**
     * Lazy-injected because {@link com.minsbot.agent.tools.LocalModelTools} transitively
     * depends on {@code ChatService}, which injects {@link OfflineModeService} — hot
     * Spring circular-dep without the {@code @Lazy} proxy.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.minsbot.agent.tools.LocalModelTools localModelTools;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.minsbot.agent.AsyncMessageService asyncMessages;

    public OfflineModeService() {
        load();
    }

    /**
     * If the persisted flag restored offline mode ON at startup, we still need to
     * auto-switch the active chat client to a local model — otherwise a user with
     * no cloud API key has {@code chatClient == null} and the main loop silently
     * drops every message. Runs once the Spring context is fully built so the
     * {@code @Lazy} LocalModelTools proxy is wired.
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void onAppReady() {
        if (!enabled.get() || localModelTools == null) return;
        try {
            String picked = localModelTools.autoSwitchToBestLocal();
            if (picked != null) {
                log.info("[OfflineMode] startup auto-switch → {}", picked);
            } else {
                log.warn("[OfflineMode] startup: offline ON but no local model to auto-switch to");
                if (asyncMessages != null) {
                    asyncMessages.push("🛡️ Offline mode is ON but no local model is installed. "
                            + "Install one in the Models tab or toggle offline mode off.");
                }
            }
        } catch (Exception e) {
            log.warn("[OfflineMode] startup auto-switch failed: {}", e.getMessage());
        }
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

            // Auto-switch the active chat client to the best installed local model so
            // the user isn't stuck with a cloud-only model while the shield is on.
            if (localModelTools != null) {
                try {
                    String picked = localModelTools.autoSwitchToBestLocal();
                    if (picked != null) {
                        log.info("[OfflineMode] auto-switched chat model → {}", picked);
                        if (asyncMessages != null) {
                            asyncMessages.push("🛡️ Offline mode ON — switched chat to local `" + picked + "`.");
                        }
                    } else {
                        log.warn("[OfflineMode] no local model available to auto-switch to");
                        if (asyncMessages != null) {
                            asyncMessages.push("🛡️ Offline mode ON — but no local model is installed. "
                                    + "Install one in the Models tab (e.g. llama3.1:8b), "
                                    + "or chat will refuse until you do.");
                        }
                    }
                } catch (Exception e) {
                    log.warn("[OfflineMode] auto-switch failed: {}", e.getMessage());
                }
            }
        }
    }

    public synchronized void disable() {
        if (enabled.compareAndSet(true, false)) {
            enabledAt = null;
            persist("0");
            log.info("[OfflineMode] DISABLED — cloud APIs re-enabled.");

            // Restore the saved cloud client if we had one. If the user never had a
            // cloud client to begin with (local-only setup), this is a no-op.
            if (localModelTools != null) {
                try {
                    String restored = localModelTools.autoRestoreCloud();
                    if (restored != null) {
                        log.info("[OfflineMode] restored chat model → {}", restored);
                        if (asyncMessages != null) {
                            asyncMessages.push("☁️ Offline mode OFF — chat restored to cloud provider.");
                        }
                    }
                } catch (Exception e) {
                    log.warn("[OfflineMode] restore failed: {}", e.getMessage());
                }
            }
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
