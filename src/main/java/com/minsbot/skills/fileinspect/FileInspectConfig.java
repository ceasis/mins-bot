package com.minsbot.skills.fileinspect;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FileInspectConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.fileinspect")
    public FileInspectProperties fileInspectProperties() { return new FileInspectProperties(); }
    public static class FileInspectProperties {
        private boolean enabled = false;
        private long maxFileBytes = 200_000_000;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public long getMaxFileBytes() { return maxFileBytes; }
        public void setMaxFileBytes(long v) { this.maxFileBytes = v; }
    }
}
