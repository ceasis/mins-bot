package com.minsbot.skills.reminders;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RemindersConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.reminders")
    public RemindersProperties remindersProperties() {
        return new RemindersProperties();
    }

    public static class RemindersProperties {
        private boolean enabled = false;
        private String storageDir = "memory/reminders";
        private int pollIntervalSeconds = 10;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getStorageDir() { return storageDir; }
        public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
        public int getPollIntervalSeconds() { return pollIntervalSeconds; }
        public void setPollIntervalSeconds(int pollIntervalSeconds) { this.pollIntervalSeconds = pollIntervalSeconds; }
    }
}
