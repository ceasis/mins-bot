package com.minsbot.skills.watcher;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WatcherConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.watcher")
    public WatcherProperties watcherProperties() {
        return new WatcherProperties();
    }

    public static class WatcherProperties {
        private boolean enabled = false;
        private String storageDir = "memory/watchers";
        private int tickIntervalSeconds = 30;
        private int minIntervalSeconds = 60;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getStorageDir() { return storageDir; }
        public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
        public int getTickIntervalSeconds() { return tickIntervalSeconds; }
        public void setTickIntervalSeconds(int tickIntervalSeconds) { this.tickIntervalSeconds = tickIntervalSeconds; }
        public int getMinIntervalSeconds() { return minIntervalSeconds; }
        public void setMinIntervalSeconds(int minIntervalSeconds) { this.minIntervalSeconds = minIntervalSeconds; }
    }
}
