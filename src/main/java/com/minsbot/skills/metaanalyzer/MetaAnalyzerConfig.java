package com.minsbot.skills.metaanalyzer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetaAnalyzerConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.metaanalyzer")
    public MetaAnalyzerProperties metaAnalyzerProperties() {
        return new MetaAnalyzerProperties();
    }

    public static class MetaAnalyzerProperties {
        private boolean enabled = false;
        private int timeoutMs = 10_000;
        private int maxBytes = 2_000_000;
        private String userAgent = "MinsBot-MetaAnalyzer/1.0";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getMaxBytes() { return maxBytes; }
        public void setMaxBytes(int maxBytes) { this.maxBytes = maxBytes; }
        public String getUserAgent() { return userAgent; }
        public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    }
}
