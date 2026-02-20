package com.botsfer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Receives Signal messages via signal-cli-rest-api webhook.
 * Configure signal-cli to forward incoming messages to /api/signal/webhook.
 * See https://github.com/bbernhard/signal-cli-rest-api
 */
@RestController
@RequestMapping("/api/signal")
public class SignalWebhookController {

    private static final Logger log = LoggerFactory.getLogger(SignalWebhookController.class);

    private final SignalApiClient signalApi;
    private final ChatService chatService;

    public SignalWebhookController(SignalApiClient signalApi, ChatService chatService) {
        this.signalApi = signalApi;
        this.chatService = chatService;
    }

    @PostMapping("/webhook")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Void> webhook(@RequestBody Map<String, Object> payload) {
        if (payload == null || !signalApi.isConfigured()) return ResponseEntity.ok().build();

        Object envelopeObj = payload.get("envelope");
        if (!(envelopeObj instanceof Map)) return ResponseEntity.ok().build();
        Map<String, Object> envelope = (Map<String, Object>) envelopeObj;

        String source = (String) envelope.get("source");
        Object dataObj = envelope.get("dataMessage");
        if (!(dataObj instanceof Map) || source == null) return ResponseEntity.ok().build();
        Map<String, Object> data = (Map<String, Object>) dataObj;

        String message = (String) data.get("message");
        if (message == null || message.isBlank()) return ResponseEntity.ok().build();

        String reply = chatService.getReply(message);
        try {
            signalApi.sendMessage(source, reply);
        } catch (Exception e) {
            log.warn("Failed to send Signal reply: {}", e.getMessage());
        }
        return ResponseEntity.ok().build();
    }
}
