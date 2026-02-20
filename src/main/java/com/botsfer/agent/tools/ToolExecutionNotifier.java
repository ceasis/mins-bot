package com.botsfer.agent.tools;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Collects tool execution status messages so the frontend can show
 * what the AI is doing while a request is in-flight.
 */
@Component
public class ToolExecutionNotifier {

    private final ConcurrentLinkedQueue<String> statusMessages = new ConcurrentLinkedQueue<>();

    /** Called by @Tool methods to report what they're about to do. */
    public void notify(String message) {
        statusMessages.add(message);
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
