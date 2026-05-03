package com.minsbot.skills.watch_clipboard;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WatchClipboardConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.watch-clipboard")
    public Properties watchClipboardProperties() {
        return new Properties();
    }

    public static class Properties {
        private boolean enabled = true;
        private String storageDir = "memory/watch_clipboard";
        /** Master poll cadence — actual checks per watcher are gated by the per-record interval. */
        private int pollIntervalSeconds = 2;
        /** Min per-watcher interval (seconds). Stop the LLM from setting absurd 100ms polls. */
        private int minIntervalSeconds = 2;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getStorageDir() { return storageDir; }
        public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
        public int getPollIntervalSeconds() { return pollIntervalSeconds; }
        public void setPollIntervalSeconds(int v) { this.pollIntervalSeconds = v; }
        public int getMinIntervalSeconds() { return minIntervalSeconds; }
        public void setMinIntervalSeconds(int v) { this.minIntervalSeconds = v; }
    }
}
