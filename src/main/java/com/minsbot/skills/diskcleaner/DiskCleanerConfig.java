package com.minsbot.skills.diskcleaner;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DiskCleanerConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.diskcleaner")
    public DiskCleanerProperties diskCleanerProperties() { return new DiskCleanerProperties(); }
    public static class DiskCleanerProperties {
        private boolean enabled = false;
        private long minFileBytes = 10_000_000; // 10MB default for "big files"
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public long getMinFileBytes() { return minFileBytes; }
        public void setMinFileBytes(long v) { this.minFileBytes = v; }
    }
}
