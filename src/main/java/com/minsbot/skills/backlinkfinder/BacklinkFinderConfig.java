package com.minsbot.skills.backlinkfinder;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BacklinkFinderConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.backlinkfinder")
    public BacklinkFinderProperties backlinkFinderProperties() { return new BacklinkFinderProperties(); }

    public static class BacklinkFinderProperties {
        private boolean enabled = false;
        private int timeoutMs = 8000;
        private int maxFetchBytes = 2_000_000;
        private int maxSites = 10;
        private String userAgent = "MinsBot-Backlink/1.0";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getMaxFetchBytes() { return maxFetchBytes; }
        public void setMaxFetchBytes(int maxFetchBytes) { this.maxFetchBytes = maxFetchBytes; }
        public int getMaxSites() { return maxSites; }
        public void setMaxSites(int maxSites) { this.maxSites = maxSites; }
        public String getUserAgent() { return userAgent; }
        public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    }
}
