package com.minsbot.skills.filestats;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FileStatsConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.filestats")
    public FileStatsProperties fileStatsProperties() { return new FileStatsProperties(); }
    public static class FileStatsProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
    }
}
