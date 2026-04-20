package com.minsbot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Gate for mouse-control tools (screen click, mouse move). Before the bot
 * physically drives the mouse, it must have a valid permission grant:
 *  - Allow Today     → valid until local-midnight
 *  - Allow 3 Hours   → valid until now + 3h
 *  - Don't Allow     → explicitly denied (valid until local-midnight to avoid repeat prompts)
 *
 * State is in-memory and resets every app start (fail-safe).
 */
@Service
public class MousePermissionService {

    private static final Logger log = LoggerFactory.getLogger(MousePermissionService.class);

    public enum Decision { ALLOWED, DENIED, UNSET }

    private static final class Grant {
        final Decision decision;
        final Instant expiresAt;
        Grant(Decision d, Instant e) { this.decision = d; this.expiresAt = e; }
    }

    private final AtomicReference<Grant> current = new AtomicReference<>(null);

    /** True if the bot currently has permission to move/click the mouse. */
    public boolean isAllowed() {
        Grant g = current.get();
        if (g == null) return false;
        if (g.decision != Decision.ALLOWED) return false;
        if (Instant.now().isAfter(g.expiresAt)) {
            current.compareAndSet(g, null);
            return false;
        }
        return true;
    }

    /** Returns the current decision state (used by the check-gate to decide messaging). */
    public Decision currentDecision() {
        Grant g = current.get();
        if (g == null) return Decision.UNSET;
        if (Instant.now().isAfter(g.expiresAt)) {
            current.compareAndSet(g, null);
            return Decision.UNSET;
        }
        return g.decision;
    }

    /** Time remaining on the current grant (for status messages). */
    public long minutesRemaining() {
        Grant g = current.get();
        if (g == null) return 0;
        long sec = java.time.Duration.between(Instant.now(), g.expiresAt).getSeconds();
        return Math.max(0, sec / 60);
    }

    public void allowToday() {
        Instant expiry = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
        current.set(new Grant(Decision.ALLOWED, expiry));
        log.info("[MousePermission] Allowed until midnight ({})", expiry);
    }

    public void allowFor3Hours() {
        Instant expiry = Instant.now().plusSeconds(3 * 3600);
        current.set(new Grant(Decision.ALLOWED, expiry));
        log.info("[MousePermission] Allowed for 3 hours (until {})", expiry);
    }

    public void deny() {
        // Remember denial until midnight so we don't spam the user with prompts.
        Instant expiry = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
        current.set(new Grant(Decision.DENIED, expiry));
        log.info("[MousePermission] Denied until midnight ({})", expiry);
    }

    public void clear() {
        current.set(null);
        log.info("[MousePermission] Cleared");
    }
}
