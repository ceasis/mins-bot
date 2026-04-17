package com.minsbot.skills.readability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ReadabilityConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.readability")
    public ReadabilityProperties readabilityProperties() {
        return new ReadabilityProperties();
    }

    public static class ReadabilityProperties {
        private boolean enabled = false;
        private int maxTextChars = 500_000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxTextChars() { return maxTextChars; }
        public void setMaxTextChars(int maxTextChars) { this.maxTextChars = maxTextChars; }
    }
}
