package com.minsbot.skills.watch_folder;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WatchFolderConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.watch-folder")
    public Properties watchFolderProperties() {
        return new Properties();
    }

    public static class Properties {
        private boolean enabled = true;
        private String storageDir = "memory/watch_folders";
        /** Debounce burst events into one notification (ms). */
        private int debounceMs = 1500;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getStorageDir() { return storageDir; }
        public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
        public int getDebounceMs() { return debounceMs; }
        public void setDebounceMs(int debounceMs) { this.debounceMs = debounceMs; }
    }
}
