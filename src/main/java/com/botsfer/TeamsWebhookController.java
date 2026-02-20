package com.botsfer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Receives Microsoft Teams Bot Framework activity callbacks.
 * Set the Messaging Endpoint in Azure Bot registration to /api/teams/messages.
 * See https://learn.microsoft.com/en-us/azure/bot-service/rest-api/bot-framework-rest-connector-api-reference
 */
@RestController
@RequestMapping("/api/teams")
public class TeamsWebhookController {

    private static final Logger log = LoggerFactory.getLogger(TeamsWebhookController.class);

    private final TeamsApiClient teamsApi;
    private final ChatService chatService;

    public TeamsWebhookController(TeamsApiClient teamsApi, ChatService chatService) {
        this.teamsApi = teamsApi;
        this.chatService = chatService;
    }

    @PostMapping("/messages")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Void> messages(@RequestBody Map<String, Object> activity) {
        if (activity == null || !teamsApi.isConfigured()) return ResponseEntity.ok().build();

        String type = (String) activity.get("type");
        if (!"message".equals(type)) return ResponseEntity.ok().build();

        String text = (String) activity.get("text");
        String serviceUrl = (String) activity.get("serviceUrl");
        String activityId = (String) activity.get("id");
        Object conversationObj = activity.get("conversation");
        if (!(conversationObj instanceof Map)) return ResponseEntity.ok().build();
        String conversationId = (String) ((Map<String, Object>) conversationObj).get("id");

        if (text == null || serviceUrl == null || conversationId == null) return ResponseEntity.ok().build();

        // Strip bot mention from text (Teams prepends "<at>BotName</at> ")
        text = text.replaceAll("<at>.*?</at>\\s*", "").trim();

        String reply = chatService.getReply(text);
        try {
            teamsApi.replyToActivity(serviceUrl, conversationId, activityId, reply);
        } catch (Exception e) {
            log.warn("Failed to send Teams reply: {}", e.getMessage());
        }
        return ResponseEntity.ok().build();
    }
}
