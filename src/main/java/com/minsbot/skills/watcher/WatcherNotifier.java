package com.minsbot.skills.watcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minsbot.agent.tools.EmailTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class WatcherNotifier {

    private static final Logger log = LoggerFactory.getLogger(WatcherNotifier.class);

    private final EmailTools emailTools;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public WatcherNotifier(EmailTools emailTools) {
        this.emailTools = emailTools;
    }

    public void notifyInStock(Watcher w, String detail) {
        boolean any = false;
        if (w.notifyEmail != null && !w.notifyEmail.isBlank()) {
            sendEmail(w, detail);
            any = true;
        }
        if (w.notifyWebhook != null && !w.notifyWebhook.isBlank()) {
            sendWebhook(w, detail);
            any = true;
        }
        if (!any) {
            log.warn("[Watcher] {} flipped to in-stock but no notifyEmail or notifyWebhook set", w.id);
        }
    }

    private void sendEmail(Watcher w, String detail) {
        String subject = "[Mins Bot] In stock: " + (w.label != null ? w.label : w.url);
        String body = """
                Good news! Your watcher triggered.

                Label:  %s
                Detail: %s
                URL:    %s

                Open the product page to complete checkout:
                %s

                — Mins Bot Watcher
                """.formatted(
                w.label != null ? w.label : "(unnamed)",
                detail, w.url, w.url);
        try {
            emailTools.sendEmail(w.notifyEmail, subject, body);
            log.info("[Watcher] Emailed {} for watcher {}", w.notifyEmail, w.id);
        } catch (Exception e) {
            log.error("[Watcher] Email send failed for {}: {}", w.id, e.getMessage());
        }
    }

    /**
     * Auto-detect provider from the URL and POST an appropriate payload.
     *  - discord.com / discordapp.com   → {"content": "..."}
     *  - pushover.net                   → {"token","user","title","message","url"} (URL-form)
     *  - ntfy.sh / any other            → plain text body (works for ntfy + most generic webhooks)
     */
    private void sendWebhook(Watcher w, String detail) {
        String url = w.notifyWebhook.trim();
        String label = w.label != null ? w.label : "(unnamed)";
        String text = "✅ In stock: " + label + "\n" + detail + "\n" + w.url;
        try {
            HttpRequest req;
            if (url.contains("discord.com") || url.contains("discordapp.com")) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("content", text);
                String json = mapper.writeValueAsString(body);
                req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();
            } else if (url.contains("pushover.net")) {
                // Pushover expects application/x-www-form-urlencoded.
                // The user must pre-fill ?token=...&user=... in the webhook URL OR we cannot route.
                // Simplest: assume URL is the API endpoint and embed token+user as form fields.
                // For now, just append message — user should put credentials in URL query string.
                String form = "message=" + java.net.URLEncoder.encode(text, java.nio.charset.StandardCharsets.UTF_8)
                        + "&title=" + java.net.URLEncoder.encode("Mins Bot — " + label, java.nio.charset.StandardCharsets.UTF_8)
                        + "&url=" + java.net.URLEncoder.encode(w.url, java.nio.charset.StandardCharsets.UTF_8);
                req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build();
            } else {
                // Generic / ntfy.sh — plain text body. ntfy uses Title/Click headers if present.
                req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "text/plain; charset=utf-8")
                        .header("Title", "Mins Bot — " + label)
                        .header("Click", w.url)
                        .header("Tags", "shopping_cart")
                        .POST(HttpRequest.BodyPublishers.ofString(text))
                        .build();
            }
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                log.warn("[Watcher] Webhook returned {} for {}: {}", resp.statusCode(), w.id, resp.body());
            } else {
                log.info("[Watcher] Webhook {} → {} for {}", resp.statusCode(), shortHost(url), w.id);
            }
        } catch (Exception e) {
            log.error("[Watcher] Webhook send failed for {}: {}", w.id, e.getMessage());
        }
    }

    private static String shortHost(String url) {
        try { return URI.create(url).getHost(); } catch (Exception e) { return url; }
    }
}
