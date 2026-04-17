package com.minsbot.skills.hashcalc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HashCalcConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.hashcalc")
    public HashCalcProperties hashCalcProperties() {
        return new HashCalcProperties();
    }

    public static class HashCalcProperties {
        private boolean enabled = false;
        private long maxFileBytes = 500_000_000L;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getMaxFileBytes() { return maxFileBytes; }
        public void setMaxFileBytes(long maxFileBytes) { this.maxFileBytes = maxFileBytes; }
    }
}
