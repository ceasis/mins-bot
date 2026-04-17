package com.minsbot.skills.okrtracker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OkrTrackerConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.okrtracker")
    public OkrTrackerProperties okrTrackerProperties() { return new OkrTrackerProperties(); }

    public static class OkrTrackerProperties {
        private boolean enabled = false;
        private String storageDir = "memory/okrs";
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getStorageDir() { return storageDir; }
        public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
    }
}
