package com.minsbot.skills.marketresearch;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MarketResearchConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.marketresearch")
    public MarketResearchProperties marketResearchProperties() {
        return new MarketResearchProperties();
    }

    public static class MarketResearchProperties {
        private boolean enabled = false;
        private int timeoutMs = 8000;
        private int maxFetchBytes = 2_000_000;
        private int maxSources = 20;
        private int maxResults = 50;
        private String userAgent = "MinsBot-MarketResearch/1.0";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getMaxFetchBytes() { return maxFetchBytes; }
        public void setMaxFetchBytes(int maxFetchBytes) { this.maxFetchBytes = maxFetchBytes; }
        public int getMaxSources() { return maxSources; }
        public void setMaxSources(int maxSources) { this.maxSources = maxSources; }
        public int getMaxResults() { return maxResults; }
        public void setMaxResults(int maxResults) { this.maxResults = maxResults; }
        public String getUserAgent() { return userAgent; }
        public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    }
}
