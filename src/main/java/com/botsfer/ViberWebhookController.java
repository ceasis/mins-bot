package com.botsfer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Receives Viber webhook callbacks. Must be exposed at a public HTTPS URL for Viber to reach it.
 * Use ngrok or similar for local development.
 */
@RestController
@RequestMapping("/api/viber")
public class ViberWebhookController {

    private static final Logger log = LoggerFactory.getLogger(ViberWebhookController.class);

    private final ViberApiClient viberApi;
    private final ChatService chatService;

    public ViberWebhookController(ViberApiClient viberApi, ChatService chatService) {
        this.viberApi = viberApi;
        this.chatService = chatService;
    }

    /**
     * Viber sends all callbacks here (webhook verification, messages, subscribed, etc.).
     * Always return 200 so Viber considers the webhook valid.
     */
    @PostMapping("/webhook")
    public ResponseEntity<Void> webhook(@RequestBody Map<String, Object> payload) {
        if (payload == null) {
            return ResponseEntity.ok().build();
        }
        String event = (String) payload.get("event");
        if (event == null) {
            return ResponseEntity.ok().build();
        }
        switch (event) {
            case "webhook":
                log.info("Viber webhook verified");
                break;
            case "message":
                handleMessage(payload);
                break;
            case "conversation_started":
                handleConversationStarted(payload);
                break;
            case "subscribed":
            case "unsubscribed":
                log.debug("Viber {} event", event);
                break;
            default:
                log.trace("Viber event: {}", event);
        }
        return ResponseEntity.ok().build();
    }

    @SuppressWarnings("unchecked")
    private void handleMessage(Map<String, Object> payload) {
        if (!viberApi.isConfigured()) return;
        Object senderObj = payload.get("sender");
        Object messageObj = payload.get("message");
        if (!(senderObj instanceof Map) || !(messageObj instanceof Map)) return;
        Map<String, Object> sender = (Map<String, Object>) senderObj;
        Map<String, Object> message = (Map<String, Object>) messageObj;
        String receiverId = (String) sender.get("id");
        String type = (String) message.get("type");
        if (receiverId == null) return;
        if ("text".equals(type)) {
            String text = (String) message.get("text");
            if (text == null) text = "";
            String reply = chatService.getReply(text);
            try {
                viberApi.sendTextMessage(receiverId, reply);
            } catch (Exception e) {
                log.warn("Failed to send Viber reply: {}", e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void handleConversationStarted(Map<String, Object> payload) {
        if (!viberApi.isConfigured()) return;
        Object userObj = payload.get("user");
        if (!(userObj instanceof Map)) return;
        Map<String, Object> user = (Map<String, Object>) userObj;
        String userId = (String) user.get("id");
        if (userId == null) return;
        String welcome = chatService.getReply("hello");
        try {
            viberApi.sendTextMessage(userId, welcome);
        } catch (Exception e) {
            log.warn("Failed to send Viber welcome: {}", e.getMessage());
        }
    }
}
