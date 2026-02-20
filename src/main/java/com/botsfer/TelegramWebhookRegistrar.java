package com.botsfer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class TelegramWebhookRegistrar {

    private static final Logger log = LoggerFactory.getLogger(TelegramWebhookRegistrar.class);

    private final TelegramApiClient telegramApi;
    private final TelegramConfig.TelegramProperties properties;

    public TelegramWebhookRegistrar(TelegramApiClient telegramApi, TelegramConfig.TelegramProperties properties) {
        this.telegramApi = telegramApi;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!telegramApi.isConfigured()) return;
        String url = properties.getWebhookUrl();
        if (url == null || url.isBlank()) return;
        try {
            var result = telegramApi.setWebhook(url);
            if (Boolean.TRUE.equals(result.get("ok"))) {
                log.info("Telegram webhook registered: {}", url);
            } else {
                log.warn("Telegram setWebhook failed: {}", result.get("description"));
            }
        } catch (Exception e) {
            log.warn("Telegram setWebhook error: {}", e.getMessage());
        }
    }
}
