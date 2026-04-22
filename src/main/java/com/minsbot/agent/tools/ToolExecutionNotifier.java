package com.minsbot.agent.tools;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Collects tool execution status messages so the frontend can show
 * what the AI is doing while a request is in-flight.
 */
@Component
public class ToolExecutionNotifier {

    private final ConcurrentLinkedQueue<String> statusMessages = new ConcurrentLinkedQueue<>();
    /** Per-turn tool usage log. Populated alongside {@link #statusMessages} but never drained
     *  by the status poller so the reply path can attach a "tools used" footnote. */
    private final CopyOnWriteArrayList<String> turnLog = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<String>> progressMirrors = new CopyOnWriteArrayList<>();
    private volatile Consumer<String> soundListener;

    /** Optional listeners (e.g. background agent jobs) receive every {@link #notify} without draining the main queue. */
    public void addProgressMirror(Consumer<String> listener) {
        if (listener != null) progressMirrors.add(listener);
    }

    public void removeProgressMirror(Consumer<String> listener) {
        progressMirrors.remove(listener);
    }

    /** Register a listener that gets called on every tool notification (used by WorkingSoundService). */
    public void setSoundListener(Consumer<String> listener) {
        this.soundListener = listener;
    }

    /** Messages that fire too frequently — suppress from status queue and mirrors. */
    private static final java.util.Set<String> QUIET_MESSAGES = java.util.Set.of(
            "Reading clipboard...",
            "Checking task status..."
    );

    /** Called by @Tool methods to report what they're about to do. */
    public void notify(String message) {
        if (QUIET_MESSAGES.contains(message)) return;
        statusMessages.add(message);
        // Track for the per-turn footnote. Skip trivial duplicates of the last entry
        // so "Waiting on ComfyUI…" doesn't show up 40 times in one summary.
        if (turnLog.isEmpty() || !message.equals(turnLog.get(turnLog.size() - 1))) {
            turnLog.add(message);
        }
        for (Consumer<String> mirror : progressMirrors) {
            try {
                mirror.accept(message);
            } catch (Exception ignored) {
                // mirrors must not break tools
            }
        }
        Consumer<String> listener = soundListener;
        if (listener != null) {
            try {
                listener.accept(message);
            } catch (Exception ignored) {
                // sound errors must never break tool execution
            }
        }
    }

    /** Drains all pending status messages (called by the polling endpoint). */
    public List<String> drain() {
        List<String> result = new ArrayList<>();
        String msg;
        while ((msg = statusMessages.poll()) != null) {
            result.add(msg);
        }
        return result;
    }

    /** Returns and clears the accumulated turn log (used for "tools used" footnote). */
    public List<String> drainTurnLog() {
        List<String> snapshot = new ArrayList<>(turnLog);
        turnLog.clear();
        return snapshot;
    }

    /** Clears any stale messages (called at start of each chat request). */
    public void clear() {
        statusMessages.clear();
        turnLog.clear();
    }
}
