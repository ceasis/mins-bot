package com.minsbot.skills.piiredactor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PiiRedactorConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.piiredactor")
    public PiiRedactorProperties piiRedactorProperties() { return new PiiRedactorProperties(); }
    public static class PiiRedactorProperties {
        private boolean enabled = false;
        private int maxTextChars = 1_000_000;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxTextChars() { return maxTextChars; }
        public void setMaxTextChars(int maxTextChars) { this.maxTextChars = maxTextChars; }
    }
}
