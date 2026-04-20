package com.minsbot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Server-Sent Events fanout for chat history changes. Every connected
 * client (JavaFX WebView + any browser tabs) holds one SseEmitter. When
 * TranscriptService saves a message or clears history, we push the event
 * to all emitters — no polling, sub-second propagation.
 *
 * Failed sends are swallowed and the dead emitter is evicted on the spot,
 * so a slow or disconnected client can't back-pressure save().
 */
@Service
public class ChatEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ChatEventPublisher.class);

    /** Emitters live for a long time; list writes are rare, iterations are hot. */
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /** 1 hour per emitter — EventSource auto-reconnects, so this just caps idle. */
    private static final long EMITTER_TIMEOUT_MS = 60L * 60L * 1000L;

    public SseEmitter register() {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> { emitter.complete(); emitters.remove(emitter); });
        emitter.onError(t -> emitters.remove(emitter));
        try {
            // Greet immediately so the client knows the stream is live.
            emitter.send(SseEmitter.event().name("ready").data("{}"));
        } catch (IOException e) {
            emitters.remove(emitter);
        }
        return emitter;
    }

    public void publishMessage(Map<String, Object> message) {
        broadcast("message", message);
    }

    public void publishCleared() {
        broadcast("cleared", Map.of());
    }

    private void broadcast(String name, Object payload) {
        for (SseEmitter e : emitters) {
            try {
                e.send(SseEmitter.event().name(name).data(payload));
            } catch (Exception ex) {
                // Client went away between our send and their receive — drop.
                emitters.remove(e);
                try { e.complete(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Heartbeat every 25s: most proxies and browser implementations drop
     * idle streams after 30-60s. A comment-style SSE frame keeps the
     * pipe open without landing as a "message" on the client.
     */
    @Scheduled(fixedDelay = 25_000L)
    public void heartbeat() {
        for (SseEmitter e : emitters) {
            try {
                e.send(SseEmitter.event().comment("hb"));
            } catch (Exception ex) {
                emitters.remove(e);
                try { e.complete(); } catch (Exception ignored) {}
            }
        }
        if (log.isTraceEnabled()) log.trace("[ChatStream] heartbeat -> {} emitters", emitters.size());
    }

    public int activeConnections() { return emitters.size(); }
}
