package com.minsbot.skills.fileinfo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FileInfoConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.fileinfo")
    public FileInfoProperties fileInfoProperties() { return new FileInfoProperties(); }
    public static class FileInfoProperties {
        private boolean enabled = false;
        private long maxHashBytes = 500_000_000;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public long getMaxHashBytes() { return maxHashBytes; }
        public void setMaxHashBytes(long v) { this.maxHashBytes = v; }
    }
}
