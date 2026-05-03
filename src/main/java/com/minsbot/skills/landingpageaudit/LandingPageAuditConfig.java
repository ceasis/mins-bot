package com.minsbot.skills.landingpageaudit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LandingPageAuditConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.landingpageaudit")
    public LandingPageAuditProperties landingPageAuditProperties() { return new LandingPageAuditProperties(); }

    public static class LandingPageAuditProperties {
        private boolean enabled = false;
        private int timeoutMs = 8000;
        private int maxFetchBytes = 3_000_000;
        private String userAgent = "MinsBot-LPAudit/1.0";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getMaxFetchBytes() { return maxFetchBytes; }
        public void setMaxFetchBytes(int maxFetchBytes) { this.maxFetchBytes = maxFetchBytes; }
        public String getUserAgent() { return userAgent; }
        public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    }
}
