package com.minsbot.skills.filefind;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FileFindConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.filefind")
    public FileFindProperties fileFindProperties() { return new FileFindProperties(); }
    public static class FileFindProperties {
        private boolean enabled = false;
        private int maxResults = 500;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public int getMaxResults() { return maxResults; }
        public void setMaxResults(int v) { this.maxResults = v; }
    }
}
