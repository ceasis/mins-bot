package com.botsfer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Receives Slack Events API callbacks.
 * Set the Request URL in Slack App config to /api/slack/events.
 * See https://api.slack.com/apis/events-api
 */
@RestController
@RequestMapping("/api/slack")
public class SlackEventController {

    private static final Logger log = LoggerFactory.getLogger(SlackEventController.class);

    private final SlackApiClient slackApi;
    private final ChatService chatService;

    public SlackEventController(SlackApiClient slackApi, ChatService chatService) {
        this.slackApi = slackApi;
        this.chatService = chatService;
    }

    @PostMapping("/events")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> events(@RequestBody Map<String, Object> payload) {
        if (payload == null) return ResponseEntity.ok().build();

        String type = (String) payload.get("type");

        // URL verification challenge
        if ("url_verification".equals(type)) {
            return ResponseEntity.ok(Map.of("challenge", payload.getOrDefault("challenge", "")));
        }

        // Event callback
        if ("event_callback".equals(type) && slackApi.isConfigured()) {
            Object eventObj = payload.get("event");
            if (eventObj instanceof Map) {
                Map<String, Object> event = (Map<String, Object>) eventObj;
                String eventType = (String) event.get("type");
                if ("message".equals(eventType) && event.get("bot_id") == null) {
                    String text = (String) event.get("text");
                    String channel = (String) event.get("channel");
                    if (text != null && channel != null) {
                        String reply = chatService.getReply(text);
                        try {
                            slackApi.postMessage(channel, reply);
                        } catch (Exception e) {
                            log.warn("Failed to send Slack reply: {}", e.getMessage());
                        }
                    }
                }
            }
        }

        return ResponseEntity.ok().build();
    }
}
