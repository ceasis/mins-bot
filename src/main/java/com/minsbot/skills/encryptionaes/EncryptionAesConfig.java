package com.minsbot.skills.encryptionaes;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EncryptionAesConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.encryptionaes")
    public EncryptionAesProperties encryptionAesProperties() { return new EncryptionAesProperties(); }
    public static class EncryptionAesProperties {
        private boolean enabled = false;
        private int maxInputBytes = 5_000_000;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxInputBytes() { return maxInputBytes; }
        public void setMaxInputBytes(int maxInputBytes) { this.maxInputBytes = maxInputBytes; }
    }
}
