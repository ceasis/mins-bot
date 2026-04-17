package com.minsbot.skills.httptester;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class HttpTesterConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.httptester")
    public HttpTesterProperties httpTesterProperties() { return new HttpTesterProperties(); }

    public static class HttpTesterProperties {
        private boolean enabled = false;
        private int timeoutMs = 30_000;
        private int maxResponseBytes = 10_000_000;
        private List<String> allowedHosts = List.of();
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getMaxResponseBytes() { return maxResponseBytes; }
        public void setMaxResponseBytes(int maxResponseBytes) { this.maxResponseBytes = maxResponseBytes; }
        public List<String> getAllowedHosts() { return allowedHosts; }
        public void setAllowedHosts(List<String> allowedHosts) { this.allowedHosts = allowedHosts; }
    }
}
