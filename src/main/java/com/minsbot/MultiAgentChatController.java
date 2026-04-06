package com.minsbot;

import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Multi-agent chat: spin up multiple AI personalities in a shared conversation.
 * They collaborate, debate, or answer from different perspectives.
 */
@RestController
@RequestMapping("/api/multi-agent")
public class MultiAgentChatController {

    private static final AtomicLong ID_GEN = new AtomicLong(1);

    private final Map<String, Map<String, String>> personas = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, Object>>> conversations = new ConcurrentHashMap<>();
    private final ChatService chatService;

    public MultiAgentChatController(ChatService chatService) {
        this.chatService = chatService;
        addPersona("Analyst", "You are a logical, data-driven analyst. Back claims with evidence and numbers. Be critical and precise.", "#3b82f6");
        addPersona("Creative", "You are a creative brainstormer. Think outside the box, suggest bold ideas, and use metaphors.", "#f59e0b");
        addPersona("Devil's Advocate", "You challenge every idea presented. Find flaws, ask hard questions, and push for stronger arguments.", "#ef4444");
        addPersona("Mediator", "You synthesize different viewpoints. Find common ground, summarize agreements, and propose compromises.", "#10b981");
    }

    private void addPersona(String name, String systemPrompt, String color) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("systemPrompt", systemPrompt);
        p.put("color", color);
        personas.put(name, p);
    }

    @GetMapping("/personas")
    public List<Map<String, String>> listPersonas() {
        return new ArrayList<>(personas.values());
    }

    @PostMapping("/personas")
    public Map<String, Object> createPersona(@RequestBody Map<String, String> body) {
        String name = body.getOrDefault("name", "Agent-" + ID_GEN.getAndIncrement());
        String prompt = body.getOrDefault("systemPrompt", "You are a helpful assistant.");
        String color = body.getOrDefault("color", "#8b5cf6");
        addPersona(name, prompt, color);
        return Map.of("status", "ok", "name", name);
    }

    @DeleteMapping("/personas/{name}")
    public Map<String, Object> deletePersona(@PathVariable String name) {
        personas.remove(name);
        return Map.of("status", "ok");
    }

    @PostMapping("/conversations")
    public Map<String, Object> startConversation(@RequestBody(required = false) Map<String, Object> body) {
        String id = "conv-" + ID_GEN.getAndIncrement();
        conversations.put(id, new CopyOnWriteArrayList<>());
        return Map.of("conversationId", id);
    }

    @GetMapping("/conversations/{id}")
    public Map<String, Object> getConversation(@PathVariable String id) {
        List<Map<String, Object>> msgs = conversations.getOrDefault(id, List.of());
        return Map.of("conversationId", id, "messages", msgs);
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/conversations/{id}/send")
    public Map<String, Object> sendMessage(@PathVariable String id,
                                           @RequestBody Map<String, Object> body) {
        List<Map<String, Object>> msgs = conversations.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>());

        String userMessage = (String) body.getOrDefault("message", "");
        List<String> agentNames = (List<String>) body.getOrDefault("agents", List.of());

        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        userMsg.put("timestamp", System.currentTimeMillis());
        msgs.add(userMsg);

        // Build context
        StringBuilder context = new StringBuilder();
        for (Map<String, Object> m : msgs) {
            String role = (String) m.getOrDefault("role", "");
            String agent = (String) m.getOrDefault("agent", "");
            String content = (String) m.getOrDefault("content", "");
            if ("user".equals(role)) context.append("User: ").append(content).append("\n\n");
            else context.append(agent).append(": ").append(content).append("\n\n");
        }

        List<Map<String, Object>> responses = new ArrayList<>();
        for (String agentName : agentNames) {
            Map<String, String> persona = personas.get(agentName);
            if (persona == null) continue;

            String systemPrompt = persona.get("systemPrompt")
                + "\n\nYour name is " + agentName + ". Keep responses concise (2-4 sentences max)."
                + " You are in a multi-agent discussion. Here is the conversation so far:\n" + context;

            String reply;
            try {
                reply = chatService.getDirectReply(userMessage, systemPrompt);
            } catch (Exception e) {
                reply = "[" + agentName + " error: " + e.getMessage() + "]";
            }

            Map<String, Object> agentMsg = new LinkedHashMap<>();
            agentMsg.put("role", "assistant");
            agentMsg.put("agent", agentName);
            agentMsg.put("color", persona.get("color"));
            agentMsg.put("content", reply);
            agentMsg.put("timestamp", System.currentTimeMillis());
            msgs.add(agentMsg);
            responses.add(agentMsg);
        }

        return Map.of("conversationId", id, "responses", responses);
    }

    @GetMapping("/conversations")
    public List<Map<String, Object>> listConversations() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var entry : conversations.entrySet()) {
            result.add(Map.of("id", entry.getKey(), "messageCount", entry.getValue().size()));
        }
        return result;
    }

    @DeleteMapping("/conversations/{id}")
    public Map<String, Object> deleteConversation(@PathVariable String id) {
        conversations.remove(id);
        return Map.of("status", "ok");
    }
}
