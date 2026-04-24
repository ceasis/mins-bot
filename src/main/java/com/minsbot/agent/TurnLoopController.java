package com.minsbot.agent;

import com.minsbot.ChatService;
import com.minsbot.agent.tools.ToolRouter;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manual trigger for the core turn-loop — lets you exercise it from the UI or
 * curl without routing through {@link ChatService}. Returns a structured
 * per-turn trace so you can see exactly how many model calls / tool executions
 * a task took.
 *
 * Example:
 *   curl -s localhost:8765/api/agent/run -H 'Content-Type: application/json' \
 *     -d '{"prompt":"list the files in the project root and count .java files","maxTurns":20}'
 */
@RestController
@RequestMapping("/api/agent")
public class TurnLoopController {

    private final TurnLoop turnLoop;
    private final ChatService chatService;
    private final ToolRouter toolRouter;

    public TurnLoopController(TurnLoop turnLoop, ChatService chatService, ToolRouter toolRouter) {
        this.turnLoop = turnLoop;
        this.chatService = chatService;
        this.toolRouter = toolRouter;
    }

    @PostMapping("/run")
    public Map<String, Object> run(@RequestBody Map<String, Object> body) {
        String prompt = String.valueOf(body.getOrDefault("prompt", "")).trim();
        String system = body.get("system") instanceof String s ? s : null;
        int maxTurns = body.get("maxTurns") instanceof Number n ? n.intValue() : 0;

        Map<String, Object> out = new HashMap<>();
        if (prompt.isBlank()) { out.put("error", "prompt is required"); return out; }

        var chatClient = chatService.getChatClient();
        if (chatClient == null) { out.put("error", "chat client not configured"); return out; }

        // Convert ToolRouter's @Tool-annotated beans into explicit ToolCallbacks so the
        // loop can drive tool execution with internalToolExecutionEnabled=false.
        Object[] toolBeans = toolRouter.selectTools(prompt);
        List<ToolCallback> tools = List.of(
                MethodToolCallbackProvider.builder()
                        .toolObjects(toolBeans)
                        .build()
                        .getToolCallbacks());

        List<Map<String, Object>> trace = new ArrayList<>();
        TurnLoop.Result result = turnLoop.run(
                chatClient,
                system,
                prompt,
                tools,
                maxTurns,
                evt -> {
                    Map<String, Object> t = new HashMap<>();
                    t.put("turn", evt.turn());
                    t.put("assistantText", evt.assistant().getText());
                    var calls = evt.assistant().getToolCalls();
                    t.put("toolCallCount", calls == null ? 0 : calls.size());
                    if (calls != null && !calls.isEmpty()) {
                        List<String> names = new ArrayList<>();
                        for (var c : calls) names.add(c.name());
                        t.put("toolCalls", names);
                    }
                    t.put("toolResponseCount", evt.toolResponses().size());
                    trace.add(t);
                }
        );

        out.put("reason", result.reason().name());
        out.put("turns", result.turns());
        out.put("finalText", result.finalText());
        out.put("trace", trace);
        return out;
    }
}
