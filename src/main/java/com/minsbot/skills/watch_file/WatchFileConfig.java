package com.minsbot.skills.watch_file;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WatchFileConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.watch-file")
    public Properties watchFileProperties() {
        return new Properties();
    }

    public static class Properties {
        private boolean enabled = true;
        /** Folder under user.home where individual watcher records are persisted. */
        private String storageDir = "memory/watch_files";
        /** How often the scheduler ticks; per-watcher intervalSeconds gates actual work. */
        private int tickIntervalSeconds = 30;
        /** Minimum acceptable per-watcher interval. */
        private int minIntervalSeconds = 30;
        /** Hard cap on per-file content size when mode=hash or mode=regex (bytes). */
        private long maxContentBytes = 10_000_000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getStorageDir() { return storageDir; }
        public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
        public int getTickIntervalSeconds() { return tickIntervalSeconds; }
        public void setTickIntervalSeconds(int v) { this.tickIntervalSeconds = v; }
        public int getMinIntervalSeconds() { return minIntervalSeconds; }
        public void setMinIntervalSeconds(int v) { this.minIntervalSeconds = v; }
        public long getMaxContentBytes() { return maxContentBytes; }
        public void setMaxContentBytes(long v) { this.maxContentBytes = v; }
    }
}
