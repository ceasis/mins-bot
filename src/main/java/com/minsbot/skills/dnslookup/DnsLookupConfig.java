package com.minsbot.skills.dnslookup;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DnsLookupConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.dnslookup")
    public DnsLookupProperties dnsLookupProperties() {
        return new DnsLookupProperties();
    }

    public static class DnsLookupProperties {
        private boolean enabled = false;
        private int timeoutMs = 5000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    }
}
