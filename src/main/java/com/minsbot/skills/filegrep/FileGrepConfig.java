package com.minsbot.skills.filegrep;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FileGrepConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.filegrep")
    public FileGrepProperties fileGrepProperties() { return new FileGrepProperties(); }
    public static class FileGrepProperties {
        private boolean enabled = false;
        private int maxFileBytes = 5_000_000;
        private int maxMatches = 1000;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public int getMaxFileBytes() { return maxFileBytes; }
        public void setMaxFileBytes(int v) { this.maxFileBytes = v; }
        public int getMaxMatches() { return maxMatches; }
        public void setMaxMatches(int v) { this.maxMatches = v; }
    }
}
