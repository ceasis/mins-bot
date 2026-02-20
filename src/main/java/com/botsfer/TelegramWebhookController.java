package com.botsfer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Receives Telegram webhook updates. Expose at a public HTTPS URL.
 * See https://core.telegram.org/bots/api#setwebhook
 */
@RestController
@RequestMapping("/api/telegram")
public class TelegramWebhookController {

    private static final Logger log = LoggerFactory.getLogger(TelegramWebhookController.class);

    private final TelegramApiClient telegramApi;
    private final ChatService chatService;

    public TelegramWebhookController(TelegramApiClient telegramApi, ChatService chatService) {
        this.telegramApi = telegramApi;
        this.chatService = chatService;
    }

    @PostMapping("/webhook")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Void> webhook(@RequestBody Map<String, Object> update) {
        if (update == null || !telegramApi.isConfigured()) return ResponseEntity.ok().build();

        Object messageObj = update.get("message");
        if (!(messageObj instanceof Map)) return ResponseEntity.ok().build();

        Map<String, Object> message = (Map<String, Object>) messageObj;
        Object chatObj = message.get("chat");
        if (!(chatObj instanceof Map)) return ResponseEntity.ok().build();

        Map<String, Object> chat = (Map<String, Object>) chatObj;
        Number chatId = (Number) chat.get("id");
        String text = (String) message.get("text");

        if (chatId == null || text == null || text.isBlank()) return ResponseEntity.ok().build();

        String reply = chatService.getReply(text);
        try {
            telegramApi.sendMessage(chatId.longValue(), reply);
        } catch (Exception e) {
            log.warn("Failed to send Telegram reply: {}", e.getMessage());
        }
        return ResponseEntity.ok().build();
    }
}
