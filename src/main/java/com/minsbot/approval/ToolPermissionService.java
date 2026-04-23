package com.minsbot.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Source of truth for the Approval Gate. Tool methods annotated with
 * {@link RequiresApproval} funnel through {@link #require} which blocks the
 * calling thread until the user picks one of four grants (or Bypass Mode
 * shortcuts the check entirely).
 *
 * <p>Grant tiers:</p>
 * <ul>
 *   <li>{@link Grant#ALLOW_ONCE} — this one call; next call re-prompts.</li>
 *   <li>{@link Grant#ALLOW_TODAY} — valid until midnight local time.</li>
 *   <li>{@link Grant#ALLOW_ALWAYS} — persisted forever (until user revokes).</li>
 *   <li>{@link Grant#DENY_ONCE} — this one call is refused; next call re-prompts.</li>
 * </ul>
 *
 * <p>Bypass Mode is an in-memory {@link AtomicBoolean}. When on, every annotated
 * method auto-approves without prompting. Resets on JVM restart — intentional:
 * a "just let me work" mode that doesn't leave the bot dangerously permissive
 * after reboot.</p>
 */
@Service
public class ToolPermissionService {

    private static final Logger log = LoggerFactory.getLogger(ToolPermissionService.class);
    private static final Path STORE = Paths.get("memory", "tool_permissions.json").toAbsolutePath();
    private static final Duration PROMPT_TIMEOUT = Duration.ofSeconds(60);

    public enum Grant {
        ALLOW_ONCE, ALLOW_TODAY, ALLOW_ALWAYS, DENY_ONCE
    }

    /** Persisted grant for a single tool method. */
    public record StoredGrant(Grant grant, String expiresIsoDate) {
        boolean isActive() {
            if (grant == Grant.ALLOW_ALWAYS) return true;
            if (grant == Grant.ALLOW_TODAY) {
                try {
                    LocalDate today = LocalDate.now();
                    return expiresIsoDate != null && !today.isAfter(LocalDate.parse(expiresIsoDate));
                } catch (Exception e) {
                    return false;
                }
            }
            return false;
        }
    }

    /** A request awaiting user decision, surfaced to the frontend via SSE. */
    public record PendingRequest(
            String requestId,
            String toolName,
            String riskLevel,
            String summary,
            long createdAtMs
    ) {}

    /** Resolved decision bundle returned to the blocked tool caller. */
    public record Decision(boolean allowed, Grant chosen, String reason) {}

    private final Map<String, StoredGrant> grants = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Decision>> pending = new ConcurrentHashMap<>();
    private final Map<String, PendingRequest> pendingMeta = new ConcurrentHashMap<>();
    private final AtomicBoolean bypassMode = new AtomicBoolean(false);
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @PostConstruct
    void load() {
        if (!Files.exists(STORE)) return;
        try {
            String raw = Files.readString(STORE);
            @SuppressWarnings("unchecked")
            Map<String, Map<String, String>> parsed = mapper.readValue(raw, Map.class);
            parsed.forEach((tool, entry) -> {
                try {
                    Grant g = Grant.valueOf(entry.get("grant"));
                    grants.put(tool, new StoredGrant(g, entry.get("expiresIsoDate")));
                } catch (Exception ignored) {}
            });
            log.info("[Approval] loaded {} persisted grant(s) from {}", grants.size(), STORE);
            // Clean up expired ALLOW_TODAY entries on boot
            grants.entrySet().removeIf(e -> !e.getValue().isActive() && e.getValue().grant() == Grant.ALLOW_TODAY);
        } catch (Exception e) {
            log.warn("[Approval] couldn't load {}: {}", STORE, e.getMessage());
        }
    }

    // ═══ Bypass mode ═════════════════════════════════════════════════

    public boolean isBypassMode() { return bypassMode.get(); }

    public void setBypassMode(boolean on) {
        bypassMode.set(on);
        log.warn("[Approval] Bypass mode = {} (in-memory, resets on restart)", on);
    }

    // ═══ Blocking gate (called by ApprovalAspect) ════════════════════

    /**
     * Blocks the calling thread until the user grants or denies. Returns a
     * {@link Decision} — callers should throw if {@code allowed} is false.
     *
     * @param toolName fully-qualified tool method id (e.g. {@code FileSystemTools.deleteFile})
     * @param risk risk tier from the annotation
     * @param summary human-readable summary to show in the modal
     */
    public Decision require(String toolName, RiskLevel risk, String summary) {
        if (bypassMode.get()) {
            return new Decision(true, null, "bypass-mode");
        }

        // SAFE never prompts (shouldn't be annotated, but defensive).
        if (risk == RiskLevel.SAFE) {
            return new Decision(true, null, "safe");
        }

        // Check persisted grants first
        StoredGrant existing = grants.get(toolName);
        if (existing != null && existing.isActive()) {
            return new Decision(true, existing.grant(), "persisted-" + existing.grant());
        }

        // Need to prompt
        String requestId = UUID.randomUUID().toString();
        PendingRequest req = new PendingRequest(
                requestId, toolName, risk.name(), summary, System.currentTimeMillis());
        CompletableFuture<Decision> future = new CompletableFuture<>();
        pending.put(requestId, future);
        pendingMeta.put(requestId, req);
        log.info("[Approval] prompting user for {} ({})", toolName, risk);

        try {
            Decision decision = future.get(PROMPT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return decision;
        } catch (TimeoutException e) {
            log.warn("[Approval] {} timed out after {}s — auto-denying", toolName, PROMPT_TIMEOUT.toSeconds());
            return new Decision(false, Grant.DENY_ONCE, "timeout");
        } catch (Exception e) {
            return new Decision(false, Grant.DENY_ONCE, "error: " + e.getMessage());
        } finally {
            pending.remove(requestId);
            pendingMeta.remove(requestId);
        }
    }

    // ═══ Called by the controller when the UI replies ════════════════

    /** User picked a grant in the modal. Unblocks the waiting tool thread. */
    public boolean resolve(String requestId, Grant grant) {
        CompletableFuture<Decision> fut = pending.get(requestId);
        PendingRequest req = pendingMeta.get(requestId);
        if (fut == null || req == null) return false;

        boolean allowed = grant != Grant.DENY_ONCE;
        if (allowed) {
            // Persist if the grant has longevity
            if (grant == Grant.ALLOW_ALWAYS) {
                grants.put(req.toolName(), new StoredGrant(grant, null));
                persist();
            } else if (grant == Grant.ALLOW_TODAY) {
                String today = LocalDate.now(ZoneId.systemDefault()).toString();
                grants.put(req.toolName(), new StoredGrant(grant, today));
                persist();
            }
        }
        fut.complete(new Decision(allowed, grant, "user-" + grant));
        return true;
    }

    // ═══ Admin / UI hooks ════════════════════════════════════════════

    public Collection<PendingRequest> pendingRequests() {
        return pendingMeta.values();
    }

    public Map<String, StoredGrant> listPersistedGrants() {
        return Map.copyOf(grants);
    }

    /** Revoke a persisted grant — future calls will re-prompt. */
    public boolean revoke(String toolName) {
        StoredGrant removed = grants.remove(toolName);
        if (removed != null) {
            persist();
            log.info("[Approval] revoked grant for {}", toolName);
            return true;
        }
        return false;
    }

    // ═══ Persistence ═════════════════════════════════════════════════

    private synchronized void persist() {
        try {
            Files.createDirectories(STORE.getParent());
            Map<String, Map<String, String>> out = new LinkedHashMap<>();
            grants.forEach((tool, g) -> {
                Map<String, String> e = new LinkedHashMap<>();
                e.put("grant", g.grant().name());
                if (g.expiresIsoDate() != null) e.put("expiresIsoDate", g.expiresIsoDate());
                out.put(tool, e);
            });
            Files.writeString(STORE, mapper.writeValueAsString(out));
        } catch (IOException e) {
            log.warn("[Approval] persist failed: {}", e.getMessage());
        }
    }
}
