package com.minsbot.skills.clipboardhistory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClipboardHistoryConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.clipboardhistory")
    public ClipboardHistoryProperties clipboardHistoryProperties() {
        return new ClipboardHistoryProperties();
    }

    public static class ClipboardHistoryProperties {
        private boolean enabled = false;
        private int maxEntries = 100;
        private int pollIntervalMs = 1500;
        private int maxEntryChars = 100_000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxEntries() { return maxEntries; }
        public void setMaxEntries(int maxEntries) { this.maxEntries = maxEntries; }
        public int getPollIntervalMs() { return pollIntervalMs; }
        public void setPollIntervalMs(int pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
        public int getMaxEntryChars() { return maxEntryChars; }
        public void setMaxEntryChars(int maxEntryChars) { this.maxEntryChars = maxEntryChars; }
    }
}
