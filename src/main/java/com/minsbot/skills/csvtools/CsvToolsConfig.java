package com.minsbot.skills.csvtools;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CsvToolsConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.csvtools")
    public CsvToolsProperties csvToolsProperties() { return new CsvToolsProperties(); }

    public static class CsvToolsProperties {
        private boolean enabled = false;
        private int maxInputBytes = 10_000_000;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxInputBytes() { return maxInputBytes; }
        public void setMaxInputBytes(int maxInputBytes) { this.maxInputBytes = maxInputBytes; }
    }
}
