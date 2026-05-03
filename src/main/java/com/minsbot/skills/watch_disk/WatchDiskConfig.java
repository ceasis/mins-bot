package com.minsbot.skills.watch_disk;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WatchDiskConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.watch-disk")
    public Properties watchDiskProperties() {
        return new Properties();
    }

    public static class Properties {
        private boolean enabled = true;
        private String storageDir = "memory/watch_disk";
        private int tickIntervalSeconds = 60;
        private int minIntervalSeconds = 60;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getStorageDir() { return storageDir; }
        public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
        public int getTickIntervalSeconds() { return tickIntervalSeconds; }
        public void setTickIntervalSeconds(int v) { this.tickIntervalSeconds = v; }
        public int getMinIntervalSeconds() { return minIntervalSeconds; }
        public void setMinIntervalSeconds(int v) { this.minIntervalSeconds = v; }
    }
}
