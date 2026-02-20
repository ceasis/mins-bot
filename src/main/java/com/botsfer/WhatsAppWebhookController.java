package com.botsfer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Receives WhatsApp Cloud API webhooks.
 * Set the callback URL in Meta Developer Portal to /api/whatsapp/webhook.
 * See https://developers.facebook.com/docs/whatsapp/cloud-api/webhooks
 */
@RestController
@RequestMapping("/api/whatsapp")
public class WhatsAppWebhookController {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookController.class);

    private final WhatsAppApiClient whatsAppApi;
    private final WhatsAppConfig.WhatsAppProperties properties;
    private final ChatService chatService;

    public WhatsAppWebhookController(WhatsAppApiClient whatsAppApi,
                                     WhatsAppConfig.WhatsAppProperties properties,
                                     ChatService chatService) {
        this.whatsAppApi = whatsAppApi;
        this.properties = properties;
        this.chatService = chatService;
    }

    /** Webhook verification (GET). Meta sends hub.mode, hub.verify_token, hub.challenge. */
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
    public ResponseEntity<Void> webhook(@RequestBody Map<String, Object> payload) {
        if (payload == null || !whatsAppApi.isConfigured()) return ResponseEntity.ok().build();

        Object entryObj = payload.get("entry");
        if (!(entryObj instanceof List)) return ResponseEntity.ok().build();

        for (Object e : (List<?>) entryObj) {
            if (!(e instanceof Map)) continue;
            Map<String, Object> entry = (Map<String, Object>) e;
            Object changesObj = entry.get("changes");
            if (!(changesObj instanceof List)) continue;
            for (Object c : (List<?>) changesObj) {
                if (!(c instanceof Map)) continue;
                Map<String, Object> change = (Map<String, Object>) c;
                Object valueObj = change.get("value");
                if (!(valueObj instanceof Map)) continue;
                Map<String, Object> value = (Map<String, Object>) valueObj;
                Object msgsObj = value.get("messages");
                if (!(msgsObj instanceof List)) continue;
                for (Object m : (List<?>) msgsObj) {
                    if (!(m instanceof Map)) continue;
                    Map<String, Object> msg = (Map<String, Object>) m;
                    String type = (String) msg.get("type");
                    String from = (String) msg.get("from");
                    if ("text".equals(type) && from != null) {
                        Object textObj = msg.get("text");
                        String body = textObj instanceof Map ? (String) ((Map<String, Object>) textObj).get("body") : "";
                        String reply = chatService.getReply(body);
                        try {
                            whatsAppApi.sendTextMessage(from, reply);
                        } catch (Exception ex) {
                            log.warn("Failed to send WhatsApp reply: {}", ex.getMessage());
                        }
                    }
                }
            }
        }
        return ResponseEntity.ok().build();
    }
}
