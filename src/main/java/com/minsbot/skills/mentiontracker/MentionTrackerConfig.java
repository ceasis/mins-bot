package com.minsbot.skills.mentiontracker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MentionTrackerConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.mentiontracker")
    public MentionTrackerProperties mentionTrackerProperties() { return new MentionTrackerProperties(); }

    public static class MentionTrackerProperties {
        private boolean enabled = false;
        private int timeoutMs = 8000;
        private int maxFetchBytes = 2_000_000;
        private int maxSources = 20;
        private String storageDir = "memory/mentions";
        private String userAgent = "MinsBot-Mention/1.0";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getMaxFetchBytes() { return maxFetchBytes; }
        public void setMaxFetchBytes(int maxFetchBytes) { this.maxFetchBytes = maxFetchBytes; }
        public int getMaxSources() { return maxSources; }
        public void setMaxSources(int maxSources) { this.maxSources = maxSources; }
        public String getStorageDir() { return storageDir; }
        public void setStorageDir(String v) { this.storageDir = v; }
        public String getUserAgent() { return userAgent; }
        public void setUserAgent(String v) { this.userAgent = v; }
    }
}
