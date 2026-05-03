package com.minsbot.skills.socialposter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SocialPosterConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.socialposter")
    public SocialPosterProperties socialPosterProperties() { return new SocialPosterProperties(); }

    public static class SocialPosterProperties {
        private boolean enabled = false;
        private int timeoutMs = 15000;

        // Bluesky
        private String blueskyHandle = "";   // e.g. "yourname.bsky.social"
        private String blueskyPassword = ""; // App password from Bluesky settings
        private String blueskyHost = "https://bsky.social";

        // Mastodon
        private String mastodonInstance = ""; // e.g. "https://mastodon.social"
        private String mastodonToken = "";

        // Generic webhook (Discord/Slack/Zapier/IFTTT) — fan-out posting
        private String webhookUrl = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public String getBlueskyHandle() { return blueskyHandle; }
        public void setBlueskyHandle(String v) { this.blueskyHandle = v; }
        public String getBlueskyPassword() { return blueskyPassword; }
        public void setBlueskyPassword(String v) { this.blueskyPassword = v; }
        public String getBlueskyHost() { return blueskyHost; }
        public void setBlueskyHost(String v) { this.blueskyHost = v; }
        public String getMastodonInstance() { return mastodonInstance; }
        public void setMastodonInstance(String v) { this.mastodonInstance = v; }
        public String getMastodonToken() { return mastodonToken; }
        public void setMastodonToken(String v) { this.mastodonToken = v; }
        public String getWebhookUrl() { return webhookUrl; }
        public void setWebhookUrl(String v) { this.webhookUrl = v; }
    }
}
