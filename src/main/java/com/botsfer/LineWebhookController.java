package com.botsfer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Receives LINE Messaging API webhook events.
 * Set the Webhook URL in LINE Developer Console to /api/line/webhook.
 * See https://developers.line.biz/en/docs/messaging-api/receiving-messages/
 */
@RestController
@RequestMapping("/api/line")
public class LineWebhookController {

    private static final Logger log = LoggerFactory.getLogger(LineWebhookController.class);

    private final LineApiClient lineApi;
    private final ChatService chatService;

    public LineWebhookController(LineApiClient lineApi, ChatService chatService) {
        this.lineApi = lineApi;
        this.chatService = chatService;
    }

    @PostMapping("/webhook")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Void> webhook(@RequestBody Map<String, Object> payload) {
        if (payload == null || !lineApi.isConfigured()) return ResponseEntity.ok().build();

        Object eventsObj = payload.get("events");
        if (!(eventsObj instanceof List)) return ResponseEntity.ok().build();

        for (Object e : (List<?>) eventsObj) {
            if (!(e instanceof Map)) continue;
            Map<String, Object> event = (Map<String, Object>) e;
            String type = (String) event.get("type");
            if (!"message".equals(type)) continue;

            Object messageObj = event.get("message");
            if (!(messageObj instanceof Map)) continue;
            Map<String, Object> message = (Map<String, Object>) messageObj;
            if (!"text".equals(message.get("type"))) continue;

            String text = (String) message.get("text");
            String replyToken = (String) event.get("replyToken");
            if (text == null || replyToken == null) continue;

            String reply = chatService.getReply(text);
            try {
                lineApi.replyMessage(replyToken, reply);
            } catch (Exception ex) {
                log.warn("Failed to send LINE reply: {}", ex.getMessage());
            }
        }
        return ResponseEntity.ok().build();
    }
}
