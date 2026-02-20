package com.botsfer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Optionally registers the Viber webhook URL on startup when app.viber.webhook-url is set.
 */
@Component
public class ViberWebhookRegistrar {

    private static final Logger log = LoggerFactory.getLogger(ViberWebhookRegistrar.class);

    private final ViberApiClient viberApi;
    private final ViberConfig.ViberProperties viberProperties;

    public ViberWebhookRegistrar(ViberApiClient viberApi, ViberConfig.ViberProperties viberProperties) {
        this.viberApi = viberApi;
        this.viberProperties = viberProperties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!viberApi.isConfigured()) return;
        String url = viberProperties.getWebhookUrl();
        if (url == null || url.isBlank()) return;
        try {
            var result = viberApi.setWebhook(url);
            Object status = result.get("status");
            if (Integer.valueOf(0).equals(status)) {
                log.info("Viber webhook registered: {}", url);
            } else {
                log.warn("Viber set_webhook failed: {}", result.get("status_message"));
            }
        } catch (Exception e) {
            log.warn("Viber set_webhook error: {}", e.getMessage());
        }
    }
}
