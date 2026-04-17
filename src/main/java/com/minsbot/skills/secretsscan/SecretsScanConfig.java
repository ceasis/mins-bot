package com.minsbot.skills.secretsscan;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SecretsScanConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.secretsscan")
    public SecretsScanProperties secretsScanProperties() {
        return new SecretsScanProperties();
    }

    public static class SecretsScanProperties {
        private boolean enabled = false;
        private int maxTextChars = 5_000_000;
        private int redactKeepChars = 4;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxTextChars() { return maxTextChars; }
        public void setMaxTextChars(int maxTextChars) { this.maxTextChars = maxTextChars; }
        public int getRedactKeepChars() { return redactKeepChars; }
        public void setRedactKeepChars(int redactKeepChars) { this.redactKeepChars = redactKeepChars; }
    }
}
