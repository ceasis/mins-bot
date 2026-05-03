package com.minsbot.skills.dailybriefing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DailyBriefingConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.dailybriefing")
    public DailyBriefingProperties dailyBriefingProperties() { return new DailyBriefingProperties(); }

    public static class DailyBriefingProperties {
        private boolean enabled = false;
        private String storageDir = "memory/briefings";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getStorageDir() { return storageDir; }
        public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
    }
}
