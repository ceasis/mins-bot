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

    /** Called by @Tool methods to report what they're about to do. */
    public void notify(String message) {
        statusMessages.add(message);
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

    /** Clears any stale messages (called at start of each chat request). */
    public void clear() {
        statusMessages.clear();
    }
}
