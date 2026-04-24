package com.minsbot.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Multi-turn agent loop. Wraps a single {@link ChatClient} invocation so that the
 * LLM can request additional iterations — appending {@code [[CONTINUE]]} at the end
 * of a reply triggers another pass with the same tool set + conversation context.
 *
 * <p>Opt-in by design: the LLM must explicitly emit the marker. Simple chats still
 * take exactly one API call, so there's no token-cost penalty for ordinary use.
 * Heavy multi-step tasks ("research X and save a report") can iterate up to the
 * configured step cap without the user having to type "keep going".</p>
 *
 * <p>Each iteration's reply is concatenated into the final string returned to the
 * caller. The {@code [[CONTINUE]]} marker itself is stripped.</p>
 */
@Service
public class AgentLoopService {

    private static final Logger log = LoggerFactory.getLogger(AgentLoopService.class);

    private static final Pattern CONTINUE_MARKER =
            Pattern.compile("\\[\\[\\s*CONTINUE\\s*]]\\s*$", Pattern.CASE_INSENSITIVE);

    @org.springframework.beans.factory.annotation.Value("${app.agent.max-steps:10}")
    private volatile int configuredMaxSteps;

    public int getMaxSteps() { return configuredMaxSteps; }
    public synchronized void setMaxSteps(int v) {
        this.configuredMaxSteps = Math.max(1, Math.min(100, v));
    }

    /**
     * Runs the provided single-shot chat call (the {@code shotCall} supplier) repeatedly
     * as long as the LLM ends its response with {@code [[CONTINUE]]}. Concatenates each
     * reply with a blank line between turns.
     *
     * <p>Caller is responsible for ensuring the chat-memory / transcript is updated
     * between iterations if Spring AI's {@link org.springframework.ai.chat.memory.ChatMemory}
     * is configured — the memory bean auto-appends each shot's messages.</p>
     *
     * @param shotCall produces the content of one chat round. Typically:
     *                 {@code chatClient.prompt().system(...).user("continue").tools(...).call().content()}.
     * @param maxSteps hard cap to avoid runaway loops. Pass {@code <= 0} for the default (10).
     */
    public String runUntilDone(Supplier<String> shotCall, int maxSteps) {
        int cap = (maxSteps <= 0) ? configuredMaxSteps : maxSteps;
        StringBuilder combined = new StringBuilder();
        int step = 0;
        while (step < cap) {
            step++;
            String reply = shotCall.get();
            if (reply == null) break;
            String stripped = CONTINUE_MARKER.matcher(reply).replaceAll("").trim();
            if (!combined.isEmpty()) combined.append("\n\n");
            combined.append(stripped);
            boolean wantsMore = CONTINUE_MARKER.matcher(reply).find();
            if (!wantsMore) {
                log.debug("[AgentLoop] stopped at step {} — no CONTINUE marker", step);
                break;
            }
            if (step == cap) {
                log.info("[AgentLoop] step cap {} reached — forcing stop", cap);
                combined.append("\n\n_(agent loop hit the ")
                        .append(cap)
                        .append("-step cap — ask me to keep going if you need more)_");
            } else {
                log.info("[AgentLoop] CONTINUE at step {} — iterating", step);
            }
        }
        return combined.toString();
    }

    /** Quick predicate: does this reply signal the LLM wants another turn? */
    public boolean wantsContinuation(String reply) {
        return reply != null && CONTINUE_MARKER.matcher(reply).find();
    }

    /** Strip the marker from a reply (for displaying the reply cleanly to the user). */
    public String stripMarker(String reply) {
        if (reply == null) return null;
        return CONTINUE_MARKER.matcher(reply).replaceAll("").trim();
    }
}
