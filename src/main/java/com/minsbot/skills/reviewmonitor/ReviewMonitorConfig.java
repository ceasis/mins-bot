package com.minsbot.skills.reviewmonitor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ReviewMonitorConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.reviewmonitor")
    public ReviewMonitorProperties reviewMonitorProperties() { return new ReviewMonitorProperties(); }

    public static class ReviewMonitorProperties {
        private boolean enabled = false;
        private int timeoutMs = 8000;
        private int maxFetchBytes = 2_000_000;
        private int maxSources = 10;
        private String userAgent = "MinsBot-ReviewMonitor/1.0";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getMaxFetchBytes() { return maxFetchBytes; }
        public void setMaxFetchBytes(int maxFetchBytes) { this.maxFetchBytes = maxFetchBytes; }
        public int getMaxSources() { return maxSources; }
        public void setMaxSources(int maxSources) { this.maxSources = maxSources; }
        public String getUserAgent() { return userAgent; }
        public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    }
}
