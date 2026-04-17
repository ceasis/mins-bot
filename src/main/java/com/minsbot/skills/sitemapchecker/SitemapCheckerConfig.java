package com.minsbot.skills.sitemapchecker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SitemapCheckerConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.sitemapchecker")
    public SitemapCheckerProperties sitemapCheckerProperties() {
        return new SitemapCheckerProperties();
    }

    public static class SitemapCheckerProperties {
        private boolean enabled = false;
        private int timeoutMs = 15_000;
        private int maxUrlsToCheck = 50;
        private int maxSitemapBytes = 10_000_000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getMaxUrlsToCheck() { return maxUrlsToCheck; }
        public void setMaxUrlsToCheck(int maxUrlsToCheck) { this.maxUrlsToCheck = maxUrlsToCheck; }
        public int getMaxSitemapBytes() { return maxSitemapBytes; }
        public void setMaxSitemapBytes(int maxSitemapBytes) { this.maxSitemapBytes = maxSitemapBytes; }
    }
}
