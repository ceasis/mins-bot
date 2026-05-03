package com.minsbot.skills.socialposter;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills/socialposter")
public class SocialPosterController {

    private final SocialPosterService service;
    private final SocialPosterConfig.SocialPosterProperties props;

    public SocialPosterController(SocialPosterService service, SocialPosterConfig.SocialPosterProperties props) {
        this.service = service;
        this.props = props;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of("skill", "socialposter", "enabled", props.isEnabled(),
                "blueskyConfigured", !props.getBlueskyHandle().isBlank() && !props.getBlueskyPassword().isBlank(),
                "mastodonConfigured", !props.getMastodonInstance().isBlank() && !props.getMastodonToken().isBlank(),
                "webhookConfigured", !props.getWebhookUrl().isBlank(),
                "purpose", "Publish posts to Bluesky / Mastodon / generic webhook"));
    }

    @PostMapping("/post")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> post(@RequestBody Map<String, Object> body) {
        if (!props.isEnabled()) return ResponseEntity.status(403)
                .body(Map.of("error", "socialposter skill is disabled"));
        try {
            String text = (String) body.get("text");
            List<String> platforms = (List<String>) body.getOrDefault("platforms", List.of());
            return ResponseEntity.ok(service.post(text, platforms));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
