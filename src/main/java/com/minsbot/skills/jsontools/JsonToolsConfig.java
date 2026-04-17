package com.minsbot.skills.jsontools;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JsonToolsConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.jsontools")
    public JsonToolsProperties jsonToolsProperties() {
        return new JsonToolsProperties();
    }

    public static class JsonToolsProperties {
        private boolean enabled = false;
        private int maxInputBytes = 5_000_000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxInputBytes() { return maxInputBytes; }
        public void setMaxInputBytes(int maxInputBytes) { this.maxInputBytes = maxInputBytes; }
    }
}
