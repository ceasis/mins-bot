package com.botsfer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Receives Discord Interactions (slash commands, messages via webhook).
 * Set the Interactions Endpoint URL in the Discord Developer Portal to /api/discord/interactions.
 * See https://discord.com/developers/docs/interactions/receiving-and-responding
 */
@RestController
@RequestMapping("/api/discord")
public class DiscordWebhookController {

    private static final Logger log = LoggerFactory.getLogger(DiscordWebhookController.class);

    private final DiscordApiClient discordApi;
    private final ChatService chatService;

    public DiscordWebhookController(DiscordApiClient discordApi, ChatService chatService) {
        this.discordApi = discordApi;
        this.chatService = chatService;
    }

    @PostMapping("/interactions")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> interactions(@RequestBody Map<String, Object> payload) {
        if (payload == null) return ResponseEntity.ok(Map.of());

        int type = payload.get("type") != null ? ((Number) payload.get("type")).intValue() : 0;

        // Type 1 = PING (Discord verification)
        if (type == 1) {
            return ResponseEntity.ok(Map.of("type", 1));
        }

        // Type 2 = APPLICATION_COMMAND (slash commands)
        if (type == 2) {
            Object dataObj = payload.get("data");
            String text = "";
            if (dataObj instanceof Map) {
                Map<String, Object> data = (Map<String, Object>) dataObj;
                // Extract first option value if present
                Object options = data.get("options");
                if (options instanceof java.util.List) {
                    java.util.List<Map<String, Object>> optList = (java.util.List<Map<String, Object>>) options;
                    if (!optList.isEmpty()) {
                        Object val = optList.get(0).get("value");
                        text = val != null ? val.toString() : "";
                    }
                }
            }
            String reply = chatService.getReply(text);
            return ResponseEntity.ok(Map.of("type", 4, "data", Map.of("content", reply)));
        }

        return ResponseEntity.ok(Map.of());
    }
}
