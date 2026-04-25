package com.minsbot.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Frontend ↔ approval-gate bridge.
 *
 * <p>{@code GET /api/approvals/stream} — long-lived SSE. Emits a {@code pending} event
 * every time a new approval request lands, plus a {@code heartbeat} every 15 s so
 * proxies don't kill the connection.</p>
 *
 * <p>{@code POST /api/approvals/{id}} — the modal's "Allow / Deny / …" click.</p>
 *
 * <p>{@code POST /api/approvals/bypass} — toggle Bypass Mode for the session.</p>
 */
@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    private static final Logger log = LoggerFactory.getLogger(ApprovalController.class);

    private final ToolPermissionService permissions;
    private final CopyOnWriteArrayList<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler;

    public ApprovalController(ToolPermissionService permissions) {
        this.permissions = permissions;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "approval-sse");
            t.setDaemon(true);
            return t;
        });
        startBroadcaster();
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(Duration.ofHours(24).toMillis());
        subscribers.add(emitter);
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        emitter.onError(e -> subscribers.remove(emitter));
        // Send current state + bypass flag on connect
        try {
            emitter.send(SseEmitter.event().name("init").data(Map.of(
                    "bypass", permissions.isBypassMode(),
                    "pending", permissions.pendingRequests()
            )));
        } catch (IOException e) {
            subscribers.remove(emitter);
        }
        return emitter;
    }

    @PostMapping(value = "/{requestId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> resolve(@PathVariable String requestId,
                                     @RequestBody Map<String, Object> body) {
        String choice = body == null ? null : String.valueOf(body.get("grant"));
        ToolPermissionService.Grant grant;
        try {
            grant = ToolPermissionService.Grant.valueOf(choice);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "grant must be one of ALLOW_ONCE / ALLOW_TODAY / ALLOW_ALWAYS / DENY_ONCE"));
        }
        boolean ok = permissions.resolve(requestId, grant);
        if (!ok) return ResponseEntity.status(404).body(Map.of("error", "No pending request with that id."));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/bypass")
    public ResponseEntity<?> bypass(@RequestBody(required = false) Map<String, Object> body) {
        boolean on = body != null && Boolean.TRUE.equals(body.get("on"));
        permissions.setBypassMode(on);
        broadcast("bypass", Map.of("on", on));
        return ResponseEntity.ok(Map.of("bypass", on));
    }

    @GetMapping(value = "/grants", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> grants() {
        return ResponseEntity.ok(Map.of(
                "bypass", permissions.isBypassMode(),
                "grants", permissions.listPersistedGrants(),
                "pending", permissions.pendingRequests()
        ));
    }

    @PostMapping(value = "/revoke", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> revoke(@RequestBody Map<String, Object> body) {
        String tool = body == null ? null : (String) body.get("tool");
        if (tool == null) return ResponseEntity.badRequest().body(Map.of("error", "missing 'tool' field"));
        boolean removed = permissions.revoke(tool);
        return ResponseEntity.ok(Map.of("revoked", removed));
    }

    // ─── Broadcast loop ──────────────────────────────────────────────

    private void startBroadcaster() {
        // Push pending-request updates every 500ms. Simple poll; cheap.
        scheduler.scheduleAtFixedRate(() -> {
            try {
                var pending = permissions.pendingRequests();
                broadcast("pending-set", Map.of("pending", pending));
            } catch (Exception e) {
                log.debug("[Approval] broadcast tick failed: {}", e.getMessage());
            }
        }, 500, 500, TimeUnit.MILLISECONDS);
        // Heartbeat every 15s
        scheduler.scheduleAtFixedRate(() -> broadcast("heartbeat", Map.of()),
                15, 15, TimeUnit.SECONDS);
    }

    private void broadcast(String event, Object data) {
        for (SseEmitter e : subscribers) {
            try {
                e.send(SseEmitter.event().name(event).data(data));
            } catch (Exception ex) {
                // Client gone — drop AND tell Spring the emitter is done so the
                // async-request machinery doesn't re-dispatch the IOException
                // through the MVC pipeline (which would hit @ExceptionHandler
                // returning JSON for a text/event-stream response → cascade).
                subscribers.remove(e);
                try { e.complete(); } catch (Exception ignored) {}
            }
        }
    }
}
