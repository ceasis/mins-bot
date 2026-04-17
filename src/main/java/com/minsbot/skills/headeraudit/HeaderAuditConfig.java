package com.minsbot.skills.headeraudit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HeaderAuditConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.headeraudit")
    public HeaderAuditProperties headerAuditProperties() {
        return new HeaderAuditProperties();
    }

    public static class HeaderAuditProperties {
        private boolean enabled = false;
        private int timeoutMs = 10_000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    }
}
