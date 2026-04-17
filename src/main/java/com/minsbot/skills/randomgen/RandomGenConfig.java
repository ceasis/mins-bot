package com.minsbot.skills.randomgen;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RandomGenConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.randomgen")
    public RandomGenProperties randomGenProperties() {
        return new RandomGenProperties();
    }

    public static class RandomGenProperties {
        private boolean enabled = false;
        private int maxCount = 1000;
        private int maxStringLength = 4096;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxCount() { return maxCount; }
        public void setMaxCount(int maxCount) { this.maxCount = maxCount; }
        public int getMaxStringLength() { return maxStringLength; }
        public void setMaxStringLength(int maxStringLength) { this.maxStringLength = maxStringLength; }
    }
}
