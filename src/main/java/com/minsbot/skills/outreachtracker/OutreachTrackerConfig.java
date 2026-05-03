package com.minsbot.skills.outreachtracker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OutreachTrackerConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.outreachtracker")
    public OutreachTrackerProperties outreachTrackerProperties() { return new OutreachTrackerProperties(); }

    public static class OutreachTrackerProperties {
        private boolean enabled = false;
        private String storageDir = "memory/outreach";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getStorageDir() { return storageDir; }
        public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
    }
}
