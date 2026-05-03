package com.minsbot.skills.watch_http;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WatchHttpConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.skills.watch-http")
    public Properties watchHttpProperties() {
        return new Properties();
    }

    public static class Properties {
        private boolean enabled = true;
        private String storageDir = "memory/watch_http";
        private int tickIntervalSeconds = 60;
        private int minIntervalSeconds = 60;
        private int requestTimeoutSeconds = 12;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getStorageDir() { return storageDir; }
        public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
        public int getTickIntervalSeconds() { return tickIntervalSeconds; }
        public void setTickIntervalSeconds(int v) { this.tickIntervalSeconds = v; }
        public int getMinIntervalSeconds() { return minIntervalSeconds; }
        public void setMinIntervalSeconds(int v) { this.minIntervalSeconds = v; }
        public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
        public void setRequestTimeoutSeconds(int v) { this.requestTimeoutSeconds = v; }
    }
}
