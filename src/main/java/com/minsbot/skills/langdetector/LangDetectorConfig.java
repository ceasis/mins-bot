package com.minsbot.skills.langdetector;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LangDetectorConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.langdetector")
    public LangDetectorProperties langDetectorProperties() { return new LangDetectorProperties(); }

    public static class LangDetectorProperties {
        private boolean enabled = false;
        private int maxTextChars = 50_000;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxTextChars() { return maxTextChars; }
        public void setMaxTextChars(int maxTextChars) { this.maxTextChars = maxTextChars; }
    }
}
