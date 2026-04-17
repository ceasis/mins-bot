package com.minsbot.skills.hibpcheck;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HibpCheckConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.hibpcheck")
    public HibpCheckProperties hibpCheckProperties() {
        return new HibpCheckProperties();
    }

    public static class HibpCheckProperties {
        private boolean enabled = false;
        private int timeoutMs = 10_000;
        private String apiBase = "https://api.pwnedpasswords.com/range/";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public String getApiBase() { return apiBase; }
        public void setApiBase(String apiBase) { this.apiBase = apiBase; }
    }
}
