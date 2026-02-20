package com.minsbot.agent;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Shared queue for pushing messages to the frontend asynchronously.
 * Used by ChatService (planning, background tasks) and ScheduledTaskTools (recurring tasks).
 * The frontend polls /api/chat/async to drain messages.
 */
@Component
public class AsyncMessageService {

    private final ConcurrentLinkedQueue<String> messages = new ConcurrentLinkedQueue<>();

    /** Push a message that will appear in the chat. */
    public void push(String message) {
        if (message != null && !message.isBlank()) {
            messages.add(message);
        }
    }

    /** Returns and removes the next message, or null if empty. */
    public String poll() {
        return messages.poll();
    }
}
