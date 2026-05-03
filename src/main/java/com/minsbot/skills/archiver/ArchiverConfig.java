package com.minsbot.skills.archiver;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ArchiverConfig {
    @Bean
    @ConfigurationProperties(prefix = "app.skills.archiver")
    public ArchiverProperties archiverProperties() { return new ArchiverProperties(); }
    public static class ArchiverProperties {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
    }
}
