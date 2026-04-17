package com.minsbot.skills.hashidentifier;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HashIdentifierConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.hashidentifier")
    public HashIdentifierProperties hashIdentifierProperties() {
        return new HashIdentifierProperties();
    }

    public static class HashIdentifierProperties {
        private boolean enabled = false;
        private int maxInputLength = 4096;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxInputLength() { return maxInputLength; }
        public void setMaxInputLength(int maxInputLength) { this.maxInputLength = maxInputLength; }
    }
}
