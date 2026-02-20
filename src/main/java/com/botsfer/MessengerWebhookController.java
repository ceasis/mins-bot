package com.botsfer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Receives Facebook Messenger webhook callbacks.
 * Set the callback URL in the Meta Developer Portal to /api/messenger/webhook.
 * See https://developers.facebook.com/docs/messenger-platform/webhooks
 */
@RestController
@RequestMapping("/api/messenger")
public class MessengerWebhookController {

    private static final Logger log = LoggerFactory.getLogger(MessengerWebhookController.class);

    private final MessengerApiClient messengerApi;
    private final MessengerConfig.MessengerProperties properties;
    private final ChatService chatService;

    public MessengerWebhookController(MessengerApiClient messengerApi,
                                      MessengerConfig.MessengerProperties properties,
                                      ChatService chatService) {
        this.messengerApi = messengerApi;
        this.properties = properties;
        this.chatService = chatService;
    }

    /** Webhook verification (GET). */
    @GetMapping("/webhook")
    public ResponseEntity<String> verify(
            @RequestParam("hub.mode") String mode,
            @RequestParam("hub.verify_token") String token,
            @RequestParam("hub.challenge") String challenge) {
        if ("subscribe".equals(mode) && properties.getVerifyToken().equals(token)) {
            return ResponseEntity.ok(challenge);
        }
        return ResponseEntity.status(403).body("Forbidden");
    }

    /** Incoming message webhook (POST). */
    @PostMapping("/webhook")
    @SuppressWarnings("unchecked")
    public ResponseEntity<String> webhook(@RequestBody Map<String, Object> payload) {
        if (payload == null || !messengerApi.isConfigured()) return ResponseEntity.ok("EVENT_RECEIVED");

        Object entryObj = payload.get("entry");
        if (!(entryObj instanceof List)) return ResponseEntity.ok("EVENT_RECEIVED");

        for (Object e : (List<?>) entryObj) {
            if (!(e instanceof Map)) continue;
            Map<String, Object> entry = (Map<String, Object>) e;
            Object msgObj = entry.get("messaging");
            if (!(msgObj instanceof List)) continue;
            for (Object m : (List<?>) msgObj) {
                if (!(m instanceof Map)) continue;
                Map<String, Object> messaging = (Map<String, Object>) m;
                Object senderObj = messaging.get("sender");
                Object messageObj = messaging.get("message");
                if (!(senderObj instanceof Map) || !(messageObj instanceof Map)) continue;
                String senderId = (String) ((Map<String, Object>) senderObj).get("id");
                String text = (String) ((Map<String, Object>) messageObj).get("text");
                if (senderId != null && text != null && !text.isBlank()) {
                    String reply = chatService.getReply(text);
                    try {
                        messengerApi.sendTextMessage(senderId, reply);
                    } catch (Exception ex) {
                        log.warn("Failed to send Messenger reply: {}", ex.getMessage());
                    }
                }
            }
        }
        return ResponseEntity.ok("EVENT_RECEIVED");
    }
}
