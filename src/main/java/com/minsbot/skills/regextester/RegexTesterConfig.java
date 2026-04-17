package com.minsbot.skills.regextester;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RegexTesterConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.regextester")
    public RegexTesterProperties regexTesterProperties() {
        return new RegexTesterProperties();
    }

    public static class RegexTesterProperties {
        private boolean enabled = false;
        private int maxInputBytes = 1_000_000;
        private int maxMatches = 1000;
        private long timeoutMs = 2000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxInputBytes() { return maxInputBytes; }
        public void setMaxInputBytes(int maxInputBytes) { this.maxInputBytes = maxInputBytes; }
        public int getMaxMatches() { return maxMatches; }
        public void setMaxMatches(int maxMatches) { this.maxMatches = maxMatches; }
        public long getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
    }
}
