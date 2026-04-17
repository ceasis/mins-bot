package com.minsbot.skills.passwordstrength;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PasswordStrengthConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.passwordstrength")
    public PasswordStrengthProperties passwordStrengthProperties() {
        return new PasswordStrengthProperties();
    }

    public static class PasswordStrengthProperties {
        private boolean enabled = false;
        private int maxLength = 1024;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxLength() { return maxLength; }
        public void setMaxLength(int maxLength) { this.maxLength = maxLength; }
    }
}
