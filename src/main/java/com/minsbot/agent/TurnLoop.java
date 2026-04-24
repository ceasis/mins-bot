package com.minsbot.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Core agentic turn-loop. Mirrors the pattern used by Claude Code's queryLoop:
 *
 *   1. Send the running message history + tools to the model.
 *   2. If the assistant emitted tool_use blocks, execute them and append the
 *      tool_response messages to history. Loop back to step 1.
 *   3. If the assistant returned final text with no tool calls, exit COMPLETED.
 *
 * Operates at the ChatModel level with {@code internalToolExecutionEnabled=false}
 * so the caller (us) owns the loop. That gives explicit control over:
 *   - per-turn observability (via {@code onTurn} callback)
 *   - a user-configurable {@code maxTurns} cap
 *   - future hooks: compaction, cost gates, plan tools, stop hooks
 *
 * Spring AI's built-in ChatClient already does an inner tool-call cycle, but it's
 * a black box. This loop makes each turn a first-class event.
 */
@Service
public class TurnLoop {

    private static final Logger log = LoggerFactory.getLogger(TurnLoop.class);

    @Value("${app.agent.max-turns:50}")
    private volatile int defaultMaxTurns;

    public int getDefaultMaxTurns() { return defaultMaxTurns; }

    private final ToolCallingManager toolCallingManager = ToolCallingManager.builder().build();

    public enum StopReason { COMPLETED, MAX_TURNS, ERROR }

    /** One iteration of the loop — emitted to {@code onTurn} after tools run. */
    public record TurnEvent(int turn,
                            AssistantMessage assistant,
                            List<ToolResponseMessage> toolResponses) {}

    public record Result(StopReason reason,
                         int turns,
                         String finalText,
                         List<Message> finalMessages) {}

    /** Convenience for the common case: single user prompt, no streaming callback. */
    public Result run(ChatClient chatClient,
                      String systemPrompt,
                      String userPrompt,
                      List<ToolCallback> tools) {
        return run(chatClient, systemPrompt, userPrompt, tools, defaultMaxTurns, null);
    }

    public Result run(ChatClient chatClient,
                      String systemPrompt,
                      String userPrompt,
                      List<ToolCallback> tools,
                      int maxTurns,
                      Consumer<TurnEvent> onTurn) {
        if (chatClient == null) throw new IllegalArgumentException("chatClient is null");

        int cap = maxTurns <= 0 ? defaultMaxTurns : Math.min(maxTurns, 200);

        List<Message> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new SystemMessage(systemPrompt));
        }
        messages.add(new UserMessage(userPrompt));

        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .toolCallbacks(tools == null ? List.of() : tools)
                .build();

        String lastText = "";
        int turn = 0;
        while (turn < cap) {
            turn++;

            ChatResponse response;
            try {
                response = chatClient.prompt(new Prompt(messages, options))
                        .call()
                        .chatResponse();
            } catch (Exception e) {
                log.error("[TurnLoop] model call failed at turn {}: {}", turn, e.toString());
                return new Result(StopReason.ERROR, turn, lastText, messages);
            }
            if (response == null || response.getResult() == null) {
                log.warn("[TurnLoop] null response at turn {}", turn);
                return new Result(StopReason.ERROR, turn, lastText, messages);
            }

            AssistantMessage assistant = response.getResult().getOutput();
            String text = assistant.getText();
            if (text != null && !text.isBlank()) lastText = text;

            List<AssistantMessage.ToolCall> calls = assistant.getToolCalls();
            boolean hasTools = calls != null && !calls.isEmpty();

            if (!hasTools) {
                // Final turn: no tool calls → model is done.
                messages.add(assistant);
                if (onTurn != null) onTurn.accept(new TurnEvent(turn, assistant, List.of()));
                log.debug("[TurnLoop] completed in {} turn(s)", turn);
                return new Result(StopReason.COMPLETED, turn, lastText, messages);
            }

            // Execute the tool calls. Spring AI's ToolCallingManager takes the current
            // prompt + response and returns the updated conversation history with the
            // assistant message and all tool_response messages appended.
            ToolExecutionResult execResult;
            try {
                execResult = toolCallingManager.executeToolCalls(
                        new Prompt(messages, options), response);
            } catch (Exception e) {
                log.error("[TurnLoop] tool execution failed at turn {}: {}", turn, e.toString());
                return new Result(StopReason.ERROR, turn, lastText, messages);
            }

            List<Message> historyAfter = execResult.conversationHistory();
            List<ToolResponseMessage> toolResponses = new ArrayList<>();
            for (int i = messages.size(); i < historyAfter.size(); i++) {
                Message m = historyAfter.get(i);
                if (m instanceof ToolResponseMessage trm) toolResponses.add(trm);
            }
            messages = new ArrayList<>(historyAfter);

            if (onTurn != null) {
                onTurn.accept(new TurnEvent(turn, assistant, Collections.unmodifiableList(toolResponses)));
            }
            log.debug("[TurnLoop] turn {} — executed {} tool call(s)", turn, calls.size());

            // Early exit if any tool requested direct return (rare but honor it).
            if (execResult.returnDirect()) {
                log.debug("[TurnLoop] returnDirect=true at turn {} — stopping", turn);
                return new Result(StopReason.COMPLETED, turn, lastText, messages);
            }
        }

        log.info("[TurnLoop] hit max-turns cap of {} — stopping", cap);
        return new Result(StopReason.MAX_TURNS, turn, lastText, messages);
    }
}
