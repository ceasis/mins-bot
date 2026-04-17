package com.minsbot.skills.keywordextractor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KeywordExtractorConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.keywordextractor")
    public KeywordExtractorProperties keywordExtractorProperties() {
        return new KeywordExtractorProperties();
    }

    public static class KeywordExtractorProperties {
        private boolean enabled = false;
        private int maxTextChars = 500_000;
        private int timeoutMs = 10_000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxTextChars() { return maxTextChars; }
        public void setMaxTextChars(int maxTextChars) { this.maxTextChars = maxTextChars; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    }
}
